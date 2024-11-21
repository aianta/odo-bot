package ca.ualberta.odobot.sqlite.impl;

import ca.ualberta.odobot.semanticflow.model.StateSample;
import ca.ualberta.odobot.semanticflow.model.TrainingMaterials;
import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import ca.ualberta.odobot.snippets.Snippet;
import ca.ualberta.odobot.sqlite.LogParser;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.vertx.core.Future;
import io.vertx.core.Handler;
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
        createDynamicXpathProgressTable();
        createSemanticObjectTable();
        createSemanticSchemaTable();
    }


    public Future<JsonArray> loadDynamicXpaths(String website){
        Promise<JsonArray> promise = Promise.promise();

        pool.preparedQuery("""
            SELECT * FROM dynamic_xpaths WHERE website = ?;
        """).execute(Tuple.of(website))
                .onSuccess(results->{
                    JsonArray dynamicXpaths = new JsonArray();
                    results.forEach(row->{
                        DynamicXPath dxpath = DynamicXPath.fromRow(row);
                        dynamicXpaths.add(dxpath.toJson());
                    });

                    promise.complete(dynamicXpaths);
                })
                .onFailure(err->{
                    log.error(err.getMessage(),err);
                    promise.fail(err.getCause());
                })
        ;

        return promise.future();
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
        String sql = """
            INSERT INTO logs (key_value, timestamp, timestamp_milli, type, command, object_type, object_name, statement, parameter) VALUES 
            (?,?,?,?,?,?,?,?,?);
        """;

        Tuple params = Tuple.of(
                entry.key(),
                entry.timestamp().format(LogParser.timestampFormat),
                entry.timestampMilli(),
                entry.type(),
                entry.command(),
                entry.objectType(),
                entry.objectName(),
                entry.statement(),
                entry.parameter()
        );

        Promise<Void> promise = Promise.promise();
        return executeParameterizedQuery(promise, sql, params, ignoreUniqueConstraintViolationErrorHandler(promise));
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
        String sql = """
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
        """;

        Tuple params = Tuple.of(
                materials.getExemplarId().toString(),
                Arrays.stream(materials.getHashedDOMTree()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode(),
                Arrays.stream(materials.getHashedTerms()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode(),
                materials.getSource(),
                Arrays.stream(materials.getLabels()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll),
                materials.getDatasetName(),
                materials.getExtras().encode(),
                materials.getDomTree().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode(),
                materials.getTerms().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode()
        );

        return executeParameterizedQuery(sql, params);
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
        String sql = """
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
        """;

        Tuple params = Tuple.of(
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
        );

        return executeParameterizedQuery(sql, params);
    }

    public Future<Void> saveTrainingExemplar(JsonObject json){
        return saveExemplar(TrainingExemplar.fromJson(json));
    }


    public Future<Void> saveDynamicXpathMiningProgress(String taskId, String actionId){
        String sql = """
                INSERT INTO dynamic_xpath_mining_progress (
                    task_id, action_id) VALUES (?,?);
                """;
        Tuple params = Tuple.of(taskId, actionId);

        return executeParameterizedQuery(sql, params);

    }

    public Future<Boolean> hasBeenMinedForDynamicXpaths(String taskId, String actionId){
        Promise<Boolean> promise = Promise.promise();

        pool.preparedQuery("""
            SELECT * from dynamic_xpath_mining_progress WHERE task_id = ? and action_id = ?;
        """).execute(Tuple.of(
                taskId,
                actionId
        )).onSuccess(done->{
            if(done.size() > 0){
                promise.complete(true);
            }else{
                promise.complete(false);
            }
        });
        return promise.future();
    }

    public Future<Void> saveDynamicXpath(JsonObject xpathData, String xpathId, String nodeId) {
        return saveDynamicXpathForWebsite(xpathData, xpathId, nodeId, "-");
    }

    @Override
    public Future<Void> saveSemanticSchema(SemanticSchema schema) {
        String sql = """
                INSERT INTO semantic_schemas(
                    id, name, schema
                ) VALUES (?,?,?);
                """;

        Tuple params = Tuple.of(
                schema.getId().toString(),
                schema.getName(),
                schema.getSchema()
        );

        return executeParameterizedQuery( sql, params);
    }

    @Override
    public Future<Void> saveSemanticObject(String objectData, String objectId, String schemaId, String snippetId) {

        String sql = """
                INSERT INTO semantic_objects(
                    id,
                    schema_id,
                    snippet_id,
                    object
                ) VALUES (?,?,?,?)
                """;

        Tuple params = Tuple.of(
                objectId,
                schemaId,
                snippetId,
                objectData
        );

        return executeParameterizedQuery(sql, params);
    }

    @Override
    public Future<Void> saveDynamicXpathForWebsite(JsonObject xpathData, String xpathId, String nodeId, String website) {
        String sql = """
            INSERT INTO dynamic_xpaths (
                id, prefix, tag, suffix, source_node_id,website
            ) VALUES (?,?,?,?,?,?);
        """;

        Tuple params = Tuple.of(
                xpathId,
                xpathData.getString("prefix"),
                xpathData.getString("dynamicTag"),
                xpathData.getString("suffix"),
                nodeId,
                website
        );

        Promise<Void> promise = Promise.promise();
        return executeParameterizedQuery(promise, sql, params, mutePrivateKeyViolationError(promise));
    }

    @Override
    public Future<Void> saveSnippet(String snippet, String xpathId, String type, String sourceHTML){
        String sql = """
            INSERT INTO snippets (
                id,
                snippet, 
                dynamic_xpath,
                snippet_type,
                source_html
            ) VALUES (?,?,?,?,?)
        """;

        Tuple params = Tuple.of(
                UUID.randomUUID().toString(),
                snippet,
                xpathId,
                type,
                sourceHTML
        );

        Promise<Void> promise = Promise.promise();

        return executeParameterizedQuery(promise, sql, params, ignoreUniqueConstraintViolationErrorHandler(promise));
    }

    @Override
    public Future<Snippet> getSnippetById(String id) {

        Promise<Snippet> promise = Promise.promise();

        pool.preparedQuery("""
            SELECT * FROM snippets WHERE id = ?;
        """).execute(
                Tuple.of(id)
        ).onSuccess(result->{

            if(result.iterator().hasNext()){
                Snippet snippet = Snippet.fromRow(result.iterator().next());

                promise.complete(snippet);
            }else {
                promise.fail("Could not find snippet with id: " + id);
            }


        }).onFailure(promise::fail);


        return promise.future();
    }

    private Future<Void> saveExemplar(TrainingExemplar exemplar){

        String sql = """
               INSERT INTO training_dataset (
                   id, source, feature_vector, label, dataset_name, feature_vector_size, extras, human_feature_vector, dom_html
               ) VALUES (?,?,?,?,?,?,?,?,?);
           """;

        Tuple params = Tuple.of(
                exemplar.id().toString(),
                exemplar.source(),
                Arrays.stream(exemplar.featureVector()).mapToObj(Double::toString).collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode(),
                Arrays.stream(exemplar.labels()).mapToObj(Integer::toString).collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode(),
                exemplar.datasetName(),
                exemplar.featureVector().length,
                exemplar.extras().encode(),
                exemplar.humanFeatureVector().encode(),
                exemplar.domHTML()
        );

        return executeParameterizedQuery(sql, params);
    }



    private Future<Void> createTrainingDatasetTable(){
        return createTable("""
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
        
        """);

    }

    private Future<Void> createStateSampleTable(){
        return createTable("""
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
        """);
    }

    private Future<Void> createTrainingMaterialsTable(){
        return createTable("""
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
        """);
    }

    private Future<Void> createLogTable(){
        return createTable("""
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
        """);
    }

    private Future<Void> createDynamicXPathTable(){
        return createTable("""
            CREATE TABLE IF NOT EXISTS dynamic_xpaths(
                id text not null,
                prefix text not null,
                tag text not null,
                suffix text not null,
                source_node_id text not null,
                website not null,
                primary key (prefix, tag, suffix, website)
            )
        """);
    }

    private Future<Void> createSemanticSchemaTable(){
        return createTable("""
                CREATE TABLE IF NOT EXISTS semantic_schemas(
                    id text not null primary key, 
                    name text not null,
                    schema text not null,
                    dynamic_xpath_id not null,
                )
                """);
    }

    private Future<Void> createSemanticObjectTable(){
        return createTable("""
                CREATE TABLE IF NOT EXISTS semantic_objects(
                    id text not null primary key,
                    schema_id text not null,
                    snippet_id text not null,
                    object text not null
                    )
                """);
    }

    private Future<Void> createSnippetTable(){
        return createTable("""
            CREATE TABLE IF NOT EXISTS snippets(
                id text primary key,
                snippet text not null,
                dynamic_xpath text not null, 
                snippet_type text not null,
                source_html text not null,
                UNIQUE(snippet, dynamic_xpath, source_html)
            )
        """);
    }

    private Future<Void> createDynamicXpathProgressTable(){
        log.info("Creating progress table");
        return createTable("""
            CREATE TABLE IF NOT EXISTS dynamic_xpath_mining_progress(
                task_id text not null, 
                action_id text not null,
                primary key (task_id, action_id)
            )
            """);
    }

    private Future<Void> createTable(String sql){

        Promise<Void> promise = Promise.promise();

        pool.preparedQuery(sql).execute(result->{
            if(result.succeeded()){
                promise.complete();
            }else{
                log.error(result.cause().getMessage(), result.cause());
                promise.fail(result.cause());
            }
        });

        return promise.future();
    }


    private Future<Void> executeParameterizedQuery(String sql, Tuple parameters){
        Promise<Void> promise = Promise.promise();
        return executeParameterizedQuery(promise, sql, parameters, genericErrorHandler(promise));
    }

    /**
     * Produces an error handler that ignores 'unique constraint failed' errors from sqlite.
     * This means that the promise will return as complete even if such an error occurs and no error message will be
     * shown in the log.
     *
     * @param promise
     * @return
     */
    private Handler<Throwable> ignoreUniqueConstraintViolationErrorHandler(Promise promise){
        return (err)->{
            if(err.getMessage().contains("A UNIQUE constraint failed")){
                promise.complete();
            }else{
                log.error(err.getMessage(), err);
                promise.fail(err.getCause());
            }
        };
    }

    /**
     * Produces an error handler that mutes 'primary key constraint failed' errors from sqlite.
     * This means that the promise will still fail if the 'primary key constraint failed' error occurs, but the log message will be
     * suppressed.
     *
     * @param promise
     * @return
     */
    private Handler<Throwable> mutePrivateKeyViolationError(Promise promise){
        return (err)->{
            if(!err.getMessage().contains("A PRIMARY KEY constraint failed")){
                log.error(err.getMessage(), err);
            }
            promise.fail(err.getCause());
        };
    }

    private Handler<Throwable> genericErrorHandler(Promise promise){
        return (err)->{
            log.error(err.getMessage(),err);
            promise.fail(err.getCause());
        };
    }

    private Future<Void> executeParameterizedQuery(Promise promise, String sql, Tuple parameters, Handler<Throwable> errHandler){

        pool.preparedQuery(sql).execute(parameters)
                .onSuccess(done->promise.complete())
                .onFailure(errHandler);
        return promise.future();
    }

}
