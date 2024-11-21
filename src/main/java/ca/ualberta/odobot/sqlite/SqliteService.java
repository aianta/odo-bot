package ca.ualberta.odobot.sqlite;

import ca.ualberta.odobot.semanticflow.model.TrainingMaterials;
import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import ca.ualberta.odobot.snippets.Snippet;
import ca.ualberta.odobot.sqlite.impl.SqliteServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Set;

@ProxyGen
public interface SqliteService {

    static SqliteService create(Vertx vertx){
        return new SqliteServiceImpl(vertx);
    }

    static SqliteService createProxy(Vertx vertx, String address){
        return new SqliteServiceVertxEBProxy(vertx, address);
    }

    Future<Void> saveTrainingMaterial(JsonObject json);

    Future<JsonArray> loadTrainingMaterialsForDataset(String dataset);

    Future<Set<String>> getHarvestProgress(String dataset);

    Future<Void> saveStateSample(JsonObject json);

    Future<JsonArray> loadDynamicXpaths(String website);

    Future<Void> saveTrainingExemplar(JsonObject json);

    Future<Void> saveDynamicXpathMiningProgress(String taskId, String actionId);

    Future<Boolean> hasBeenMinedForDynamicXpaths(String taskId, String actionId);

    Future<Void> saveDynamicXpathForWebsite(JsonObject xpathData, String xpathId, String nodeId, String website);

    Future<Void> saveDynamicXpath(JsonObject xpathData, String xpathId, String nodeId);

    Future<Void> saveSemanticSchema(SemanticSchema schema);

    /**
     *
     * @param objectData the xml of the object
     * @param objectId the UUID of the object.
     * @param schemaId the UUID of the schema validating this object
     * @param snippetId the UUID of the snippet from which this object was derived.
     */
    Future<Void> saveSemanticObject(String objectData, String objectId, String schemaId, String snippetId);

    Future<Void> saveSnippet(String snippet, String xpathId, String type, String sourceHTML);

    Future<Snippet> getSnippetById(String id);

    /**
     * Returns the list of {@link ca.ualberta.odobot.sqlite.impl.TrainingExemplar} in json form
     * that belong to the training dataset with the specified name.
     *
     * Dataset names should be defined during log preprocessing.
     * @param datasetName
     * @return
     */
    Future<JsonArray> loadTrainingDataset(String datasetName);

    Future<Void> insertLogEntry(JsonObject json);

    Future<JsonArray> selectLogs(long timestampMilli, long range);


}
