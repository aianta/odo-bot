package ca.ualberta.odobot.sqlite.impl;

import ca.ualberta.odobot.semanticflow.model.StateSample;
import ca.ualberta.odobot.semanticflow.model.TrainingMaterials;
import ca.ualberta.odobot.sqlite.LogParser;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.SQLITE_CONNECTION_STRING;


public class SqliteServiceImpl implements SqliteService {

    private static final Logger log = LoggerFactory.getLogger(SqliteServiceImpl.class);
    private Vertx vertx;
    JDBCPool pool;

    public SqliteServiceImpl(Vertx vertx){
        this.vertx = vertx;
        JsonObject config = new JsonObject()
                .put("url", SQLITE_CONNECTION_STRING)
                .put("max_pool_size", 16);

        pool = JDBCPool.pool(vertx, config);

        createLogTable();
        createTrainingDatasetTable();
        createTrainingMaterialsTable();
        createStateSampleTable();
        createSnippetTable();
        createDynamicXPathTable();
    }



    public Future<JsonArray> selectLogs(long timestampMilli, long range){
        Promise promise = Promise.promise();

        pool.preparedQuery("""
            SELECT * FROM logs WHERE timestamp_milli > ? AND timestamp_milli < ?;
        
        """).execute(Tuple.of(
                timestampMilli - range, //lowerbound
                timestampMilli + range  //upperbound
        )).onSuccess(results->{

            JsonArray logs = new JsonArray();

            results.forEach(row->logs.add(DbLogEntry.fromRow(row).toJson()));
            log.info("returning {} db logs for timestamp {} and range {}", logs.size(), timestampMilli, range);

            promise.complete(logs);
        }).onFailure(err->{
            log.error(err.getMessage(), err);
            promise.fail(err);
        })
        ;

        return promise.future();
    }

    public Future<Void> insertLogEntry(JsonObject json){
        return insertLogEntry(DbLogEntry.fromJson(json));
    }

    private Future<Void> insertLogEntry(DbLogEntry entry){
        Promise<Void> promise = Promise.promise();

        pool.preparedQuery("""
            INSERT INTO logs (key_value, timestamp, timestamp_milli, type, command, object_type, object_name, statement, parameter) VALUES 
            (?,?,?,?,?,?,?,?,?);
        """).execute(Tuple.of(
                entry.key(),
                entry.timestamp().format(LogParser.timestampFormat),
                entry.timestampMilli(),
                entry.type(),
                entry.command(),
                entry.objectType(),
                entry.objectName(),
                entry.statement(),
                entry.parameter()
        )).onSuccess(done->{
            promise.complete();
        }).onFailure(err->{
            if(err.getMessage().contains("A PRIMARY KEY constraint failed")){
                log.debug("LogEntry {} already exists in database, ignoring.", entry.key());
                promise.complete();
                return;
            }
            log.error(err.getMessage(), err);
            promise.fail(err);
        });

        return promise.future();
    }


    public Future<JsonArray> loadTrainingDataset(String datasetName){
        Promise<JsonArray> promise = Promise.promise();

        pool.preparedQuery("""
            SELECT * FROM training_dataset WHERE dataset_name = ?;
        """).execute(Tuple.of(datasetName), result->{
           if(result.succeeded()){
               JsonArray dataset = new JsonArray();
               result.result().forEach(row->dataset.add(TrainingExemplar.fromRow(row).toJson()));

               promise.complete(dataset);
           }else{
               log.error(result.cause().getMessage(), result.cause());
               promise.fail(result.cause());
           }
        });


        return promise.future();
    }

    public Future<JsonArray> loadTrainingMaterialsForDataset(String dataset){
        Promise<JsonArray> promise = Promise.promise();

        pool.preparedQuery("""
            SELECT * FROM training_materials WHERE
            dataset_name = ?;
        """).execute(Tuple.of(dataset), result->{
           if(result.succeeded()){

               List<TrainingMaterials> materials = new ArrayList<>();
               RowSet<Row> rows = result.result();
               for(Row r: rows){
                   materials.add(TrainingMaterials.fromRow(r));
               }
               promise.complete(materials.stream().map(e->e.toJson()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll));

           }else{
               promise.fail(result.cause());
           }
        });

        return promise.future();
    }

    public Future<Void> saveTrainingMaterial(JsonObject json){
        log.info("Saving {}", json.encodePrettily());
        return saveTrainingMaterial(TrainingMaterials.fromJson(json));
    }
    public Future<Void> saveTrainingMaterial(TrainingMaterials materials){
        Promise<Void> promise = Promise.promise();

        pool.preparedQuery("""
            INSERT INTO  training_materials(
                exemplar_id, 
                hashed_dom_tree, 
                hashed_terms,
                source,
                labels,
                dataset_name,
                extras,
                dom_tree,
                terms
            ) VALUES (?,?,?,?,?,?,?,?,?);
        """).execute(Tuple.of(
                materials.getExemplarId().toString(),
                Arrays.stream(materials.getHashedDOMTree()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode(),
                Arrays.stream(materials.getHashedTerms()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode(),
                materials.getSource(),
                Arrays.stream(materials.getLabels()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll),
                materials.getDatasetName(),
                materials.getExtras().encode(),
                materials.getDomTree().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode(),
                materials.getTerms().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode()
        ), result->{
           if(result.succeeded()){
               log.info("Training material saved!");
               promise.complete();
           }else{
               log.error(result.cause().getMessage(), result.cause());
               promise.fail(result.cause());
           }
        });


        return promise.future();
    }

    public Future<Set<String>> getHarvestProgress(String dataset){
        Promise<Set<String>> promise = Promise.promise();

        pool.preparedQuery("""
            SELECT DISTINCT source FROM training_materials WHERE dataset_name = ?;
        """).execute(Tuple.of(dataset), result->{
           if(result.succeeded()){

               Set<String> sources = new HashSet<>(); //Set of completed es indices
               for(Row r: result.result()){
                   sources.add(r.getString("source"));
               }

               promise.complete(sources);

           }else {
               log.error(result.cause().getMessage(), result.cause());
               promise.fail(result.cause());
           }
        });

        return promise.future();
    }

    public Future<Void> saveStateSample(JsonObject json){
        return saveStateSample(StateSample.fromJson(json));
    }


    public Future<Void> saveStateSample(StateSample sample){
        Promise<Void> promise = Promise.promise();

        pool.preparedQuery("""
            INSERT INTO state_samples (
                id,
                base_uri,
                normalized_uri_path,
                dataset_name,
                source,
                source_symbol,
                extras,
                dom_tree,
                hashed_dom_tree,
                dom_html,
                vector_size
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?);
        """).execute(Tuple.of(
                sample.id.toString(),
                sample.baseURI,
                sample.normalizedBaseUri(),
                sample.datasetName,
                sample.source,
                sample.sourceSymbol,
                sample.extras.encode(),
                sample.domTree.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode(),
                Arrays.stream(sample.hashedDOMTree).collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode(),
                sample.domHTML,
                sample.domTree.size()
        ), result->{
            if(result.succeeded()){
                promise.complete();
            }else {
                promise.fail(result.cause());
            }
        });

        return promise.future();
    }
    public Future<Void> saveTrainingExemplar(JsonObject json){
        return saveExemplar(TrainingExemplar.fromJson(json));
    }


    public Future<Void> saveDynamicXpath(JsonObject xpathData, String xpathId, String nodeId) {
        return saveDynamicXpathForWebsite(xpathData, xpathId, nodeId, "-");
    }

    @Override
    public Future<Void> saveDynamicXpathForWebsite(JsonObject xpathData, String xpathId, String nodeId, String website) {
        Promise<Void> promise = Promise.promise();

        pool.preparedQuery("""
            INSERT INTO dynamic_xpaths (
                id, prefix, tag, suffix, source_node_id,website
            ) VALUES (?,?,?,?,?,?);
        """).execute(Tuple.of(
                xpathId,
                xpathData.getString("prefix"),
                xpathData.getString("dynamicTag"),
                xpathData.getString("suffix"),
                nodeId,
                website
        )).onSuccess(done->promise.complete())
                .onFailure(err->{
                    log.error(err.getMessage(), err);
                    promise.fail(err);
                });

        return promise.future();
    }

    @Override
    public Future<Void> saveSnippet(String snippet, String xpathId, String type, String sourceHTML) {
        Promise<Void> promise = Promise.promise();

        pool.preparedQuery("""
            INSERT INTO snippets (
                id,
                snippet, 
                dynamic_xpath,
                snippet_type,
                source_html
            ) VALUES (?,?,?,?,?)
        """).execute(Tuple.of(
                UUID.randomUUID().toString(),
                snippet,
                xpathId,
                type,
                sourceHTML
        )).onSuccess(done->promise.complete())
                .onFailure(err->{
                    //Uniqueness constraint errors are fine. We don't want duplicates of snippet/xpath/source.
                    if(err.getMessage().contains("[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed")){
                        promise.complete();
                    }else{
                        log.error(err.getMessage(),err);
                        promise.fail(err);
                    }
                });
        return promise.future();
    }

    private Future<Void> saveExemplar(TrainingExemplar exemplar){

        Promise<Void> promise = Promise.promise();

        pool.preparedQuery("""
            INSERT INTO training_dataset (
                id, source, feature_vector, label, dataset_name, feature_vector_size, extras, human_feature_vector, dom_html
            ) VALUES (?,?,?,?,?,?,?,?,?);
        """).execute(Tuple.of(
                exemplar.id().toString(),
                exemplar.source(),
                Arrays.stream(exemplar.featureVector()).mapToObj(Double::toString).collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode(),
                Arrays.stream(exemplar.labels()).mapToObj(Integer::toString).collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode(),
                exemplar.datasetName(),
                exemplar.featureVector().length,
                exemplar.extras().encode(),
                exemplar.humanFeatureVector().encode(),
                exemplar.domHTML()
        ),result->{
            if(result.succeeded()){
                promise.complete();
            }else{
                promise.fail(result.cause());
            }
        });

        return promise.future();

    }

    private Future<Void> createTrainingDatasetTable(){
        Promise<Void> promise = Promise.promise();

        pool.preparedQuery("""
            CREATE TABLE IF NOT EXISTS training_dataset (
                id text PRIMARY KEY,
                source text,
                feature_vector text,
                human_feature_vector text,
                label text,
                dataset_name text,
                feature_vector_size numeric,
                extras text,
                dom_html text
            )
        
        """).execute(result->{
            if(result.succeeded()){
                promise.complete();
            }else{
                promise.fail(result.cause());
            }
        });

        return promise.future();

    }

    private Future<Void> createStateSampleTable(){
        Promise<Void> promise = Promise.promise();

        pool.preparedQuery("""
            CREATE TABLE IF NOT EXISTS state_samples (
                id text PRIMARY KEY,
                base_uri text NOT NULL,
                normalized_uri_path text NOT NULL,
                dataset_name text NOT NULL,
                source text NOT NULL,
                source_symbol text NOT NULL,
                extras text NOT NULL,
                dom_tree text NOT NULL,
                hashed_dom_tree text NOT NULL,
                dom_html text NOT NULL,
                vector_size numeric NOT NULL
            )
        """).execute(result->{
            if(result.succeeded()){
                promise.complete();
            }else {
                promise.fail(result.cause());
            }
        });

        return promise.future();
    }

    private Future<Void> createTrainingMaterialsTable(){
        Promise<Void> promise = Promise.promise();

        pool.preparedQuery("""
            CREATE TABLE IF NOT EXISTS training_materials(
                hashed_dom_tree text NOT NULL,
                dom_tree text NOT NULL,
                terms text NOT NULL,
                hashed_terms text NOT NULL,
                exemplar_id text PRIMARY KEY,
                source text NOT NULL,
                labels text NOT NULL,
                dataset_name NOT NULL,
                extras text NOT NULL
            )
        """).execute(result->{
            if(result.succeeded()){
                promise.complete();
            }else{
                promise.fail(result.cause());
            }
        });

        return promise.future();
    }

    private Future<Void> createLogTable(){
        Promise<Void> promise = Promise.promise();

        pool.preparedQuery("""
            CREATE TABLE IF NOT EXISTS logs (
                key_value text PRIMARY KEY,
                timestamp text NOT NULL,
                timestamp_milli NUMERIC NOT NULL,
                type TEXT NOT NULL,
                command TEXT NOT NULL,
                object_type TEXT NOT NULL,
                object_name TEXT NOT NULL,
                statement TEXT NOT NULL,
                parameter TEXT NOT NULL
            )
        """).execute(result->{
            if(result.succeeded()){
                promise.complete();
            }else{
                promise.fail(result.cause());
            }
        });


        return promise.future();
    }

    private Future<Void> createDynamicXPathTable(){
        Promise<Void> promise = Promise.promise();

        pool.preparedQuery("""
            CREATE TABLE IF NOT EXISTS dynamic_xpaths(
                id text not null,
                prefix text not null,
                tag text not null,
                suffix text not null,
                source_node_id text not null,
                website not null,
                primary key (prefix, tag, suffix, website)
            )
        """).execute(result->{
            if(result.succeeded()){
                promise.complete();
            }else{
                promise.fail(result.cause());
            }
        });

        return promise.future();
    }

    private Future<Void> createSnippetTable(){
        Promise<Void> promise = Promise.promise();
        pool.preparedQuery("""
            CREATE TABLE IF NOT EXISTS snippets(
                id text primary key,
                snippet text not null,
                dynamic_xpath text not null, 
                snippet_type text not null,
                source_html text not null,
                UNIQUE(snippet, dynamic_xpath, source_html)
            )
        """).execute(result->{
            if(result.succeeded()){
                promise.complete();
            }else{
                promise.fail(result.cause());
            }
        });


        return promise.future();
    }




}
