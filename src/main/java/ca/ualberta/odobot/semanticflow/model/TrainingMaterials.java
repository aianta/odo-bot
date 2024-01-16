package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * A class used to store the data for producing a training exemplar.
 * This data is not yet ready to be converted into an exemplar as feature vector sizes are determined
 * by the largest features in a dataset, which is not known apriori.
 */
public class TrainingMaterials {

    private static final Logger log = LoggerFactory.getLogger(TrainingMaterials.class);

    Function<String, Future<JsonArray>> treeHashingFunction;

    ClickEvent clickEvent;
    NetworkEvent networkEvent;

    double [] hashedDOMTree;

    double [] hashedTerms;

    UUID exemplarId = UUID.randomUUID();

    String source;

    int []  labels = new int [3];

    String datasetName;

    JsonObject extras = new JsonObject();

    public TrainingMaterials(ClickEvent clickEvent, NetworkEvent networkEvent, String source, String datasetName ){
        this.clickEvent = clickEvent;
        this.networkEvent = networkEvent;
        this.source = source;
        this.datasetName = datasetName;
    }

    public Future<TrainingMaterials> harvestData(){
        log.info("Harvesting training materials.");
        return treeHashingFunction.apply(clickEvent.getDomSnapshot().outerHtml()).compose(hashedDOMJsonArray->{
            try{
                log.info("Got hashed DOM json array!");
                hashedDOMTree = hashedDOMJsonArray.stream().mapToDouble(entry->(double) entry).toArray();
                log.info("Got hashed DOM array {}", hashedDOMTree.length);

                hashedTerms = clickEvent.getSemanticArtifacts().getJsonArray("terms")
                        .stream().map(o->(String)o)
                        .mapToDouble(term->new HashCodeBuilder(41,83).append(term).toHashCode()).toArray();
                log.info("Got hashed terms array: {}", hashedTerms.length);


                int pathHash = hashString(networkEvent.getPath());
                labels[0] = pathHash;
                extras.put("path", networkEvent.getPath());
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

//                String networkHashString = networkHashStringBuilder.toString().trim();
//                label = new HashCodeBuilder(63, 87)
//                        .append(networkHashString)
//                        .toHashCode();


                String dbOpsString = networkEvent.getDbOps().stringRepresentation();
//                label = new HashCodeBuilder(63, 87)
//                        .append(dbOpsString)
//                        .toHashCode();

                log.info("Constructed Label! {}", labels.toString());

                extras.put("dbOpsString", dbOpsString);


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
