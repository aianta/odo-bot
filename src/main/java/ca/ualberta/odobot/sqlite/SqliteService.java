package ca.ualberta.odobot.sqlite;

import ca.ualberta.odobot.snippet2xml.SemanticObject;
import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import ca.ualberta.odobot.snippets.Snippet;
import ca.ualberta.odobot.sqlite.impl.SqliteServiceImpl;
import ca.ualberta.odobot.taskgenerator.canvas.CanvasTask;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;
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

    Future<Void> saveSyntheticTask(String trajectoryId,
                                   String task,
                                   String timestamp,
                                   String model,
                                   String sourceIndex);

    Future<Void> saveTrajectoryEventDescription(
            int eventIndex,
            String trajectoryId,
            String sourceIndex,
            String description,
            String symbol,
            String timestamp,
            String model
    );

    Future<Void> saveTrajectoryAPICall(
            String trajectoryId,
            int eventIndex,
            String method,
            String path,
            String operationName,
            String request,
            String response,
            String sourceIndex
    );

    Future<Void> saveStateSample(JsonObject json);

    Future<JsonArray> loadDynamicXpaths(String website);

    Future<Void> insertTask(CanvasTask task);

    Future<Void> saveTrainingExemplar(JsonObject json);

    Future<Void> saveDynamicXpathMiningProgress(String taskId, String actionId);

    Future<Boolean> hasBeenMinedForDynamicXpaths(String taskId, String actionId);

    Future<Void> saveDynamicXpathForWebsite(JsonObject xpathData, String xpathId, String nodeId, String website);

    Future<Void> saveDynamicXpath(JsonObject xpathData, String xpathId, String nodeId);

    Future<Void> saveSemanticSchema(SemanticSchema schema);

    Future<List<SemanticSchema>> getSemanticSchemas();

    Future<SemanticSchema> getSemanticSchemaById(String id);

    Future<String> getSchemaSourceNodeId(SemanticSchema schema);

    Future<List<JsonObject>> getSemanticSchemasWithSourceNodeIds();

    Future<List<String>> getUniqueDynamicXpathsFromSnippets();

    Future<List<Snippet>> getSnippets();

    Future<List<Snippet>> getSnippetsByDynamicXpath(String dynamicXpath);

    Future<List<Snippet>> sampleSnippetsForDynamicXpath(int numSamples, String dynamicXpath);

    Future<Void> saveSemanticObject(SemanticObject object);

    Future<Void> saveDataEntryInfo(JsonObject info);

    Future<List<JsonObject>> getAllDataEntryInfo();

    Future<JsonObject> getAllDataEntryInfoForXpath(String xpath);

    Future<List<JsonObject>> getAllDataEntryAnnotations();

    Future<Void> saveDataEntryAnnotation(JsonObject info);

    Future<Void> saveSnippetNoURI(String snippet, String xpathId, String type, String sourceHTML);

    Future<Void> saveSnippet(String snippet, String xpathId, String type, String sourceHTML, String baseURI);

    Future<Void> saveCommonSubstructure(String clusteringId, JsonObject data);

    Future<Set<JsonObject>> getUniqueCommonSubstructureContainers();

    Future<Void> saveCommonSubstructureContainer(String clusteringId, JsonObject data);

    Future<Set<String>> getMinedSnapshotIds(String clusteringId);

    Future<Void> saveClusteringInfo(String clusteringId, double threshold, int numPerm, double eps, int minSamples);

    Future<Snippet> getSnippetById(String id);

    Future<Void> saveHTMLAttributes(List<JsonArray> data);

    Future<Void> saveNormalizedLink(String normalizedHref, String label);

    Future<List<String>> getDistinctHrefValues();

    Future<Set<String>> getNormalizedHrefsByLabel(String label);

    Future<String> getLabelByNormalizedHref(String href);

    /**
     * Returns the estimated distance (number of events) between a source and a target node in the nav model,
     * based on the trajectories used to construct the model which contained both source and target nodes.
     * @param srcNodeId
     * @param tgtNodeId
     * @return estimated distance between source and target nav node, OR null, if the two nodes were never observed in the same trajectory together.
     */
    Future<Double> getEstimatedNavModelDistance(String srcNodeId, String tgtNodeId);

    Future<Void> saveEventNodeMapping(String eventId, String nodeId, int eventIndex, String trajectoryId);

    Future<Void> mergeEventNodeMappings(Set<String> oldNodeIds, String newNodeId);

    Future<Void> updateEventNodeMapping(String oldNodeId, String newNodeId);

    Future<Void> saveHTMLAttribute(String tag, String attribute, String value);

    /**
     * Returns the list of {@link ca.ualberta.odobot.sqlite.impl.TrainingExemplar} in json form
     * that belong to the training dataset with the specified name.
     *
     * Dataset names should be defined during log preprocessing.
     * @param datasetName
     * @return
     */
    Future<JsonArray> loadTrainingDataset(String datasetName);

    Future<List<CanvasTask>> loadTasks();

    Future<Void> updateTask(CanvasTask task);

    Future<Void> insertLogEntry(JsonObject json);

    Future<JsonArray> selectLogs(long timestampMilli, long range);

    Future<Void> saveDOMSnapshot(String id, String snapshot, String baseUri, String srcIndex);

    Future<List<JsonObject>> getDomSnapshots(Set<String> ids);

    Future<Set<String>> getResourceParameterLabels();

}
