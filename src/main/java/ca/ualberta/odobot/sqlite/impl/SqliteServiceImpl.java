package ca.ualberta.odobot.sqlite.impl;

import ca.ualberta.odobot.semanticflow.model.StateSample;
import ca.ualberta.odobot.semanticflow.model.TrainingMaterials;
import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import ca.ualberta.odobot.snippet2xml.SemanticObject;
import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import ca.ualberta.odobot.snippets.Snippet;
import ca.ualberta.odobot.sqlite.LogParser;
import ca.ualberta.odobot.sqlite.SqliteService;
import ca.ualberta.odobot.taskgenerator.canvas.CanvasTask;
import io.vertx.core.*;
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
        createDataEntriesTable();
        createDataEntryAnnotationTable();
        createTaskParameterTable();
        createTaskTable();
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

    public Future<List<JsonObject>> getAllDataEntryAnnotations(){
        Promise<List<JsonObject>> promise = Promise.promise();

        pool.preparedQuery("""
            SELECT * FROM data_entry_annotations;
        """).execute()
                .onSuccess(rows->{
                    List<JsonObject> results = new ArrayList<>();
                    for(Row row: rows){
                        JsonObject result = new JsonObject();
                        result.put("xpath", row.getString("xpath"))
                                .put("label", row.getString("label"))
                                .put("description", row.getString("description"));

                        if (row.getString("radio_group") != null) {
                            result.put("radioGroup", row.getString("radio_group"));
                        }

                        results.add(result);
                    }

                    promise.complete(results);
                })
                .onFailure(promise::fail);

        return promise.future();
    }

    public Future<List<JsonObject>> getAllDataEntryInfo(){
        Promise<List<JsonObject>> promise = Promise.promise();

        pool.preparedQuery("""
            SELECT * FROM data_entries;
        """).execute()
                .onSuccess(rows->{
                    List<JsonObject> results = new ArrayList<>();
                    for(Row row: rows){
                        JsonObject result = new JsonObject();
                        result.put("xpath", row.getString("xpath"))
                                .put("inputElement", row.getString("input_element"))
                                .put("htmlContext", row.getString("html_context"))
                                .put("enteredData", new JsonArray(row.getString("entered_data")));

                        if (row.getString("radio_group") != null) {
                            result.put("radioGroup", row.getString("radio_group"));
                        }

                        results.add(result);
                    }

                    promise.complete(results);
                })
                .onFailure(promise::fail);

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

    public Future<Void> insertTask(CanvasTask task){
        String sql = """
                INSERT INTO tasks (id, parameterized_text, plain_text, local_path, plain_prompt, parameterized_prompt)
                VALUES (?,?,?,?,?,?);
                """;

        Tuple params = Tuple.of(
                task.getId().toString(),
                task.getParameterizedTask(),
                task.getPlainTask(),
                task.getLocalPath(),
                task.getPlainPrompt(),
                task.getParameterizedPrompt()
        );

        return executeParameterizedQuery(sql, params).compose(done->insertTaskParameters(task.getId(), task.getParameters()));

    }

    public Future<Void> updateTask(CanvasTask task){
        String sql = """
                UPDATE tasks
                SET parameterized_text = ?,
                    plain_text = ?,
                    local_path = ?,
                    plain_prompt = ?,
                    parameterized_prompt = ?
                WHERE id = ?;
                """;

        Tuple params = Tuple.of(
                task.getParameterizedTask(),
                task.getPlainTask(),
                task.getLocalPath(),
                task.getPlainPrompt(),
                task.getParameterizedPrompt(),
                task.getId().toString()
        );

        return executeParameterizedQuery(sql, params);
    }

    public Future<Void> insertTaskParameters(UUID taskId, JsonObject parameters){
        String sql = """
                INSERT INTO task_parameters (task_id, name, type) VALUES (?,?,?);
                """;

        List<Future<Void>> futures = new ArrayList<>();
        Iterator<Map.Entry<String, Object>> it = parameters.iterator();
        while (it.hasNext()){
            Map.Entry<String,Object> taskParameter = it.next();
            Tuple stmtParams = Tuple.of(
                    taskId.toString(),
                    taskParameter.getKey(),
                    (String)taskParameter.getValue()
            );

            futures.add(executeParameterizedQuery(sql, stmtParams));

        }

        return Future.all(futures).compose(done->{
            log.info("Task parameters saved to database!");
            return Future.succeededFuture();
        });
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

    public Future<Void> saveDataEntryAnnotation(JsonObject info){

        String sql = """
                INSERT INTO data_entry_annotations (xpath, label, description, radio_group) VALUES (?,?,?,?);
                """;

        Tuple params = Tuple.of(
                info.getString("xpath"),
                info.getString("label"),
                info.getString("description"),
                info.containsKey("radioGroup")?info.getString("radioGroup"):null
        );

        return executeParameterizedQuery(sql, params);

    }


    public Future<SemanticSchema> getSemanticSchemaById(String id){
        Promise<SemanticSchema> promise = Promise.promise();

        pool.preparedQuery("""
            SELECT * FROM semantic_schemas WHERE id = ?;
        """)
                .execute(Tuple.of(id))
                .onSuccess(rows->{

                    if(rows.size() == 0){
                        promise.fail("Could not find schema with id: " + id);
                    }

                    assert rows.size() <= 1;

                    SemanticSchema schema = SemanticSchema.fromRow(rows.iterator().next());
                    promise.complete(schema);
                });

        return promise.future();
    }

    public Future<List<CanvasTask>> loadTasks(){

        Promise<List<CanvasTask>> promise = Promise.promise();


        //Load parameter table
        Future.all(
                pool.preparedQuery("""
                    SELECT * FROM task_parameters WHERE valid = 1;
                """).execute(),
                pool.preparedQuery("""
                    SELECT * FROM tasks;
                """).execute()
        ).onSuccess(compositeFuture -> {
                    List<CanvasTask> results = new ArrayList<>();
                    RowSet<Row> parameters = compositeFuture.resultAt(0);
                    Map<UUID, JsonObject> parameterMap = new HashMap<>();

                    /**
                     * Create a map of valid parameters to properly reconstruct the Canvas tasks
                     * The keys will be the id of the task, and the values the parameter JSON object.
                     */
                    parameters.forEach(row->{
                        //Only include valid parameters or those whose validity has not yet been decided.
                        if(row.getInteger("valid") == 1 || row.getInteger("valid") == null){
                            UUID taskId = row.getUUID("task_id");
                            JsonObject taskParameters = parameterMap.getOrDefault(taskId, new JsonObject());
                            taskParameters.put(row.getString("name"), row.getString("type"));
                            parameterMap.put(taskId, taskParameters);
                        }
                    });

                    RowSet<Row> tasks = compositeFuture.resultAt(1);

                    tasks.forEach(row->{
                        UUID id = row.getUUID("id");
                        results.add(CanvasTask.fromRow(row, parameterMap.getOrDefault(id, new JsonObject())));
                    });

                    promise.complete(results);
                })
                .onFailure(err->log.error(err.getMessage(),err));

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

    public Future<List<String>> getUniqueDynamicXpathsFromSnippets(){
        Promise<List<String>> promise = Promise.promise();
        pool.preparedQuery("""
            SELECT DISTINCT dynamic_xpath from snippets;
        """).execute()
                .onSuccess(results->{
                    List<String> dynamicXpaths = new ArrayList<>();
                    Iterator<Row> it = results.iterator();
                    while (it.hasNext()){
                        dynamicXpaths.add(it.next().getString("dynamic_xpath"));
                    }
                    promise.complete(dynamicXpaths);
                })
                .onFailure(promise::fail);

        return promise.future();
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

    public Future<List<JsonObject>> getSemanticSchemasWithSourceNodeIds(){

        Promise promise = Promise.promise();

        String sql = """
                with
                schemas as (select * from semantic_schemas a),
                dxpaths as (select id as dxpath_id, source_node_id from dynamic_xpaths b)
                select id, name, schema, dynamic_xpath_id, source_node_id from schemas a left join dxpaths b on b.dxpath_id = a.dynamic_xpath_id
                """;

        pool.preparedQuery(sql)
                .execute()
                .onSuccess(rows->{

                    List<JsonObject> results = new ArrayList<>();

                    for (Row row: rows){
                        SemanticSchema schema = SemanticSchema.fromRow(row);
                        String sourceNodeId = row.getString("source_node_id");

                        /**
                         * Need to wrap both of these values into a JsonObject, because vertx service proxies
                         * do not allow Map<SemanticSchema, String> types as responses.
                         */

                        JsonObject resultObject = schema.toJson();
                        resultObject.put("sourceNodeId", sourceNodeId);
                        results.add(resultObject);
                    }

                    promise.complete(results);
                })
                .onFailure(promise::fail);

        return promise.future();

    }

    public Future<String> getSchemaSourceNodeId(SemanticSchema schema){

        Promise promise = Promise.promise();

        String sql = """
                select source_node_id from dynamic_xpaths where id = ?;
                """;

        pool.preparedQuery(sql)
                .execute(Tuple.of(schema.getDynamicXpathId()))
                .onSuccess(rows->{

                    assert rows.size() == 1; //There should only ever be one matching dynamic xpath for a given schema, otherwise something is deeply wrong.

                    promise.complete(rows.iterator().next().getString("source_node_id"));

                })
                .onFailure(promise::fail);

        return promise.future();

    }

    public Future<List<SemanticSchema>> getSemanticSchemas(){
        log.info("Getting semantic schemas!");
        Promise<List<SemanticSchema>> promise = Promise.promise();
        String sql = """
                Select * from semantic_schemas;
                """;
        pool.preparedQuery(sql)
                .execute()
                .onSuccess(rows->{

                    List<SemanticSchema> result = new ArrayList<>();
                    for(Row row: rows){
                        result.add(SemanticSchema.fromRow(row));
                    }
                    promise.complete(result);
                })
                .onFailure(err->{
                    log.error(err.getMessage(), err);
                    promise.fail(err);
                })
        ;

        return promise.future();
    }

    @Override
    public Future<Void> saveSemanticSchema(SemanticSchema schema) {
        String sql = """
                INSERT INTO semantic_schemas(
                    id, name, schema, dynamic_xpath_id
                ) VALUES (?,?,?,?);
                """;

        Tuple params = Tuple.of(
                schema.getId().toString(),
                schema.getName() == null?"null":schema.getName(),
                schema.getSchema(),
                schema.getDynamicXpathId()
        );

        return executeParameterizedQuery( sql, params);
    }

    public Future<Void> saveSemanticObject(SemanticObject object){
        return saveSemanticObjectData(object.getObject(), object.getId().toString(), object.getSchemaId().toString(), object.getSnippetId().toString());
    }


    private Future<Void> saveSemanticObjectData(String objectData, String objectId, String schemaId, String snippetId) {

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
    public Future<List<Snippet>> getSnippets(){
        Promise<List<Snippet>> promise = Promise.promise();

        pool.preparedQuery("""
            SELECT id, snippet, dynamic_xpath, snippet_type, base_uri FROM snippets;
        """).execute()
                .onSuccess(results->{

                    List<Snippet> snippets = new ArrayList<>();
                    Iterator<Row> it = results.iterator();
                    while (it.hasNext()){
                        snippets.add(Snippet.fromRow(it.next()));
                    }

                    promise.complete(snippets);

                })
                .onFailure(promise::fail);

        return promise.future();
    }

    public Future<List<Snippet>> getSnippetsByDynamicXpath(String dynamicXpath){
        Promise<List<Snippet>> promise = Promise.promise();

        pool.preparedQuery("""
            SELECT id, snippet, dynamic_xpath, snippet_type, base_uri FROM snippets WHERE dynamic_xpath = ?;
        """).execute(Tuple.of(dynamicXpath))
                .onSuccess(results->{

                    List<Snippet> snippets = new ArrayList<>();
                    Iterator<Row> it = results.iterator();
                    while (it.hasNext()){
                        snippets.add(Snippet.fromRow(it.next()));
                    }

                    promise.complete(snippets);

                })
                .onFailure(promise::fail);

        return promise.future();
    }

    public Future<List<Snippet>> sampleSnippetsForDynamicXpath(int numSamples, String dynamicXpath){
        Promise<List<Snippet>> promise = Promise.promise();

        pool.preparedQuery("""
            SELECT id, snippet, dynamic_xpath, snippet_type, base_uri FROM snippets WHERE dynamic_xpath = ? limit ?;
        """).execute(Tuple.of(dynamicXpath, numSamples))
                .onSuccess(results->{

                    List<Snippet> snippets = new ArrayList<>();
                    Iterator<Row> it = results.iterator();
                    while (it.hasNext()){
                        snippets.add(Snippet.fromRow(it.next()));
                    }

                    promise.complete(snippets);

                })
                .onFailure(promise::fail);

        return promise.future();
    }

    @Override
    public Future<Void> saveDynamicXpathForWebsite(JsonObject xpathData, String xpathId, String nodeId, String website) {
        String sql = """
            INSERT INTO dynamic_xpaths (
                id, prefix, tag, suffix, suffix_pattern, source_node_id,website
            ) VALUES (?,?,?,?,?,?,?);
        """;

        Tuple params = Tuple.of(
                xpathId,
                xpathData.getString("prefix"),
                xpathData.getString("dynamicTag"),
                xpathData.containsKey("suffix")?xpathData.getJsonArray("suffix").encode():null,
                xpathData.containsKey("suffixPattern")?xpathData.getString("suffixPattern"):null,
                nodeId,
                website
        );

        Promise<Void> promise = Promise.promise();
        return executeParameterizedQuery(promise, sql, params, mutePrivateKeyViolationError(promise));
    }

    @Override
    public Future<Void> saveSnippetNoURI(String snippet, String xpathId, String type, String sourceHTML){
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
    public Future<Void> saveSnippet(String snippet, String xpathId, String type, String sourceHTML, String baseURI){
        String sql = """
            INSERT INTO snippets (
                id,
                snippet, 
                dynamic_xpath,
                snippet_type,
                source_html,
                base_uri
            ) VALUES (?,?,?,?,?,?)
        """;

        Tuple params = Tuple.of(
                UUID.randomUUID().toString(),
                snippet,
                xpathId,
                type,
                sourceHTML,
                baseURI
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

    public Future<Void> saveDataEntryInfo(JsonObject info){
        /**
         *
         * Basically inserting a new record if we've never seen info for this xpath before, otherwise we just update
         * the entered data json array with the new entered data value.
         *
         * Getting fancy with UPSERT Sqlite functionality.
         * https://stackoverflow.com/questions/418898/upsert-not-insert-or-replace
         *
         * Getting fancy with SQLite Json support
         * https://stackoverflow.com/questions/49451777/sqlite-append-a-new-element-to-an-existing-array
         *
         */
        String sql = """
                INSERT INTO data_entries(xpath, input_element, html_context, entered_data, radio_group) VALUES (?,?,?,?,?)
                ON CONFLICT(xpath) DO UPDATE SET entered_data = json_insert(entered_data, '$[#]', ?) WHERE NOT INSTR(entered_data, ?);
                """;

        log.info("Saving data entry info: \n{}", info.encodePrettily());

        Tuple params = null;
        params = Tuple.of(
                info.getString("xpath"),
                info.getString("input_element"),
                info.getString("html_context"),
                info.containsKey("entered_data")?new JsonArray().add(info.getString("entered_data")).encode():new JsonArray().encode(),
                info.containsKey("radio_group")?info.getString("radio_group"):null,
                info.getString("entered_data"),
                info.getString("entered_data")
        );
        
        return executeParameterizedQuery(sql, params);

    }


    private Future<Void> createDataEntryAnnotationTable(){
        return createTable("""
                CREATE TABLE IF NOT EXISTS data_entry_annotations(
                    xpath text PRIMARY KEY,
                    label text NOT NULL,
                    description text NOT NULL,
                    radio_group text
                ) 
                """);
    }

    private Future<Void> createTaskParameterTable(){
        return createTable("""
                CREATE TABLE IF NOT EXISTS task_parameters (
                    name text not null,
                    type text not null,
                    task_id text not null,
                    valid integer,
                    primary key (name, type, task_id)
                    );
                """
        );
    }

    private Future<Void> createTaskTable(){
        return createTable("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id text PRIMARY KEY,
                    parameterized_text text not null,
                    plain_text text not null,
                    local_path text not null,
                    plain_prompt text not null,
                    parameterized_prompt text not null,
                    valid integer
                )
                """);
    }

    private Future<Void> createDataEntriesTable(){
        return createTable("""
                CREATE TABLE IF NOT EXISTS data_entries(
                    xpath text PRIMARY KEY,
                    input_element text NOT NULL,
                    html_context text NOT NULL,
                    entered_data text NOT NULL,
                    radio_group text
                );
                """);
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
                suffix text,
                suffix_pattern text, 
                source_node_id text not null,
                website not null,
                primary key (prefix, tag, website)
            )
        """);
    }

    private Future<Void> createSemanticSchemaTable(){
        return createTable("""
                CREATE TABLE IF NOT EXISTS semantic_schemas(
                    id text not null primary key, 
                    name text not null,
                    schema text not null,
                    dynamic_xpath_id not null
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
                base_uri text,
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
