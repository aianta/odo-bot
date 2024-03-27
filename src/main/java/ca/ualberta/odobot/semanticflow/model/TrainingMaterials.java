package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A class used to store the data for producing a training exemplar.
 * This data is not yet ready to be converted into an exemplar as feature vector sizes are determined
 * by the largest features in a dataset, which is not known apriori.
 */
public class TrainingMaterials {

    private static final Logger log = LoggerFactory.getLogger(TrainingMaterials.class);

    Function<String, Future<JsonArray>> treeHashingFunction;

    Function<String, Future<JsonArray>> treeFlattenFunction;

    ClickEvent clickEvent;
    NetworkEvent networkEvent;

    List<String> domTree;

    List<String> terms;

    double [] hashedDOMTree;

    double [] hashedTerms;

    UUID exemplarId = UUID.randomUUID();

    String source;

    int []  labels = new int [3];

    String datasetName;

    JsonObject extras = new JsonObject();

    public static TrainingMaterials fromRow(Row r){

        TrainingMaterials result = new TrainingMaterials();
        result.source = r.getString("source");
        result.datasetName = r.getString("dataset_name");
        result.exemplarId = UUID.fromString(r.getString("exemplar_id"));
        result.extras = new JsonObject(r.getString("extras"));
        result.labels = new JsonArray(r.getString("labels")).stream().mapToInt(o->(int)o).toArray();
        result.hashedTerms = new JsonArray(r.getString("hashed_terms")).stream().mapToDouble(o->(double)o).toArray();
        result.hashedDOMTree = new JsonArray(r.getString("hashed_dom_tree")).stream().mapToDouble(o->(double)o).toArray();
        result.domTree = new JsonArray(r.getString("dom_tree")).stream().map(o->(String)o).collect(Collectors.toList());
        result.terms = new JsonArray(r.getString("terms")).stream().map(o->(String)o).collect(Collectors.toList());

        return result;
    }

    public static TrainingMaterials fromJson(JsonObject json){
        TrainingMaterials result = new TrainingMaterials();
        result.source = json.getString("source");
        result.datasetName = json.getString("datasetName");
        result.exemplarId = UUID.fromString(json.getString("exemplarId"));
        result.extras = json.getJsonObject("extras");
        result.labels = json.getJsonArray("labels").stream().mapToInt(o->(int)o).toArray();
        result.hashedTerms = json.getJsonArray("hashedTerms").stream().mapToDouble(o->(double) o).toArray();
        result.hashedDOMTree = json.getJsonArray("hashedDOMTree").stream().mapToDouble(o->(double) o).toArray();
        result.domTree = json.getJsonArray("domTree").stream().map(o->(String)o).collect(Collectors.toList());
        result.terms = json.getJsonArray("terms").stream().map(o->(String)o).collect(Collectors.toList());

        return result;
    }
    public JsonObject toJson(){
        JsonObject result = new JsonObject()
                .put("source", getSource())
                .put("datasetName", getDatasetName())
                .put("exemplarId", getExemplarId().toString())
                .put("extras", getExtras())
                .put("labels", Arrays.stream(labels).collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("hashedTerms", Arrays.stream(hashedTerms).collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("hashedDOMTree", Arrays.stream(hashedDOMTree).collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("domTree", domTree.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("terms", terms.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));


        return result;
    }

    public TrainingMaterials(){}

    public TrainingMaterials(ClickEvent clickEvent, NetworkEvent networkEvent, String source, String datasetName ){
        this.clickEvent = clickEvent;
        this.networkEvent = networkEvent;
        this.source = source;
        this.datasetName = datasetName;
    }

    public Future<TrainingMaterials> harvestData(){
        log.info("Harvesting training materials.");

        return CompositeFuture.all(
                treeHashingFunction.apply(clickEvent.getDomSnapshot().outerHtml()),
                treeFlattenFunction.apply(clickEvent.getDomSnapshot().outerHtml())
        ).onFailure(err->log.error(err.getMessage(), err))
                .compose(treeResults->{

        //return treeHashingFunction.apply(clickEvent.getDomSnapshot().outerHtml()).compose(hashedDOMJsonArray->{
            try{

                JsonArray hashedDOMJsonArray = treeResults.resultAt(0);
                JsonArray flattenedDOMTree = treeResults.resultAt(1);

                domTree = flattenedDOMTree.stream().map(o->(String)o).collect(Collectors.toList());

                log.info("Got hashed DOM json array!");
                hashedDOMTree = hashedDOMJsonArray.stream().mapToDouble(entry->(double) entry).toArray();
                log.info("Got hashed DOM array {}", hashedDOMTree.length);

                terms = clickEvent.getSemanticArtifacts().getJsonArray("terms").stream().map(o->(String)o).collect(Collectors.toList());

                hashedTerms = clickEvent.getSemanticArtifacts().getJsonArray("terms")
                        .stream().map(o->(String)o)
                        .mapToDouble(term->new HashCodeBuilder(41,83).append(term).toHashCode()).toArray();
                log.info("Got hashed terms array: {}", hashedTerms.length);
                extras.put("top_5_terms",clickEvent.getSemanticArtifacts().getJsonArray("terms").stream().limit(5).collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
                extras.put("clickEvent", clickEvent.id.toString());

                /**
                 * TODO: We need to process paths using Open API/API documentation such that
                 * permalinks are turned to wildcards appropriately. In our case, I believe
                 * our only consideration is for canvas page management routes.
                 *
                 *
                 * https://canvas.instructure.com/doc/api/pages.html#method.wiki_pages_api.update
                 *
                 * PUT /api/v1/courses/:course_id/pages/:url_or_id
                 *
                 * So there we need to turn :url_or_id into an asterisks, we'll do this with a simple regex.
                 *
                 * TODO: Ideally there would be a proper component here capable of doing this dynamically based
                 * on API documentation.
                 */
                String wildcardedPath = networkEvent.getPath().replaceAll("(?<=pages\\/)[\\s\\S]+", "*");
                String fullPath = networkEvent.getMethod() + "-" + wildcardedPath;

                //Need to include the method for best differentiability between calls.
                int pathHash = hashString(fullPath);
                labels[0] = pathHash;
                extras.put("path", fullPath);
                extras.put("pathHash", pathHash);


                String requestComponent = makeRequestString(networkEvent);
                String responseComponent = makeResponseString(networkEvent);


                if(requestComponent != null){
                    int requestHash = hashString(requestComponent);
                    labels[1] = requestHash;
                    extras.put("requestComponent", requestComponent);
                    extras.put("requestHash", requestHash);
                }else{
                    extras.put("requestComponent", "null");
                    extras.put("requestHash", 0);
                    labels[1] = 0;
                }

                if(responseComponent != null){
                    int responseHash = hashString(responseComponent);
                    labels[2] = responseHash;
                    extras.put("responseComponent", responseComponent);
                    extras.put("responseHash", responseHash);
                }else{
                    extras.put("responseComponent" ,"null");
                    extras.put("responseHash",0);
                    labels[2] = 0;
                }



                log.info("Constructed Label! {}", labels.toString());




            }catch (Exception e){
                log.error(e.getMessage(), e);
            }

            return Future.succeededFuture(this);
        });

    }

    public int [] getLabels() {
        return labels;
    }

    public double[] getHashedTerms() {
        return hashedTerms;
    }

    public double[] getHashedDOMTree() {
        return hashedDOMTree;
    }

    public Function<String, Future<JsonArray>> getTreeHashingFunction() {
        return treeHashingFunction;
    }

    public void setTreeHashingFunction(Function<String, Future<JsonArray>> treeHashingFunction) {
        this.treeHashingFunction = treeHashingFunction;
    }

    public ClickEvent getClickEvent() {
        return clickEvent;
    }

    public void setClickEvent(ClickEvent clickEvent) {
        this.clickEvent = clickEvent;
    }

    public NetworkEvent getNetworkEvent() {
        return networkEvent;
    }

    public void setNetworkEvent(NetworkEvent networkEvent) {
        this.networkEvent = networkEvent;
    }

    public UUID getExemplarId() {
        return exemplarId;
    }

    public void setExemplarId(UUID exemplarId) {
        this.exemplarId = exemplarId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDatasetName() {
        return datasetName;
    }

    public void setDatasetName(String datasetName) {
        this.datasetName = datasetName;
    }

    public JsonObject getExtras() {
        return extras;
    }

    public Function<String, Future<JsonArray>> getTreeFlattenFunction() {
        return treeFlattenFunction;
    }

    public void setTreeFlattenFunction(Function<String, Future<JsonArray>> treeFlattenFunction) {
        this.treeFlattenFunction = treeFlattenFunction;
    }

    public List<String> getDomTree() {
        return domTree;
    }

    public List<String> getTerms() {
        return terms;
    }

    private String makeRequestString(NetworkEvent event){

        if(event.getRequestObject() != null){
            return makeHashStringFromObject(event.getRequestObject());
        }

        if(event.getRequestArray() != null){
            return makeHashStringFromArray(event.getRequestArray());
        }

        log.warn("Network Event request had no body.");
        return null;
    }

    private String makeResponseString(NetworkEvent event){
        if(event.getResponseObject() != null){
            return makeHashStringFromObject(event.getResponseObject());
        }

        if(event.getResponseArray() != null){
            return makeHashStringFromArray(event.getResponseArray());
        }

        log.warn("Network Event response had no body.");
        return null;
    }

    private String makeHashStringFromObject(JsonObject input){

        return input.fieldNames()
                .stream()
                .sorted()
                .collect(
                        StringBuilder::new,
                        ((stringBuilder, s) -> stringBuilder.append(s + " ")),
                        StringBuilder::append
                ).toString().trim();
    }

    private String makeHashStringFromArray(JsonArray input){

        JsonObject firstJsonObject = input.stream()
                .filter(o->o instanceof JsonObject)
                .map(o->(JsonObject)o)
                .findFirst().orElse(null);

        if(firstJsonObject == null){
            log.warn("No json object in input json array!");
            return null;
        }

        return firstJsonObject.fieldNames()
                .stream()
                .sorted()
                .collect(
                        StringBuilder::new,
                        ((stringBuilder, s) -> stringBuilder.append(s + " ") ),
                        StringBuilder::append
                ).toString().trim();
    }

    int hashString(String s){
        return new HashCodeBuilder(63, 87)
                .append(s)
                .toHashCode();
    }
}
