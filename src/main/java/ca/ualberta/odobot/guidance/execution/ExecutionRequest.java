package ca.ualberta.odobot.guidance.execution;

import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import io.vertx.core.json.JsonArray;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ExecutionRequest {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRequest.class);

    public enum Type{
        PREDEFINED, NL
    }

    private String taskDescription;

    private int pathRecomputations = 0;
    private long timeout = 180000l; //3 minutes default

    private Type type;
    private UUID id;

    private UUID target; //Target node id.

    private Set<String> targets;

    private String userLocation;

    private List<ExecutionParameter> parameters;

    //Nodes which represent instructions that have failed to execute.
    private Set<String> failedNodes = new HashSet<>();

    private Set<String> resourceParameters; //Resolved object parameters from the execution parameters above.
    private Set<String> inputParameters; //Resolved input parameters from the execution parameters above.
    private Set<String> apiCalls; //Resolved target node ids.

    private Set<String> visitedNodes = new HashSet<>();

    private String targetMethod;
    private String targetPath;
    private String targetOperationName;

    public String getTargetOperationName() {
        return targetOperationName;
    }

    public String getTaskDescription() {
        return taskDescription;
    }

    public ExecutionRequest setTaskDescription(String taskDescription) {
        this.taskDescription = taskDescription;
        return this;
    }

    public UUID getId() {
        return id;
    }

    public ExecutionRequest setId(UUID id) {
        this.id = id;
        return this;
    }

    public long getTimeout() {
        return timeout;
    }

    public ExecutionRequest setTimeout(long timeout) {
        this.timeout = timeout;
        return this;
    }

    public UUID getTarget() {
        return target;
    }

    public ExecutionRequest setTarget(String target) {
        return this.setTarget(UUID.fromString(target));
    }

    public ExecutionRequest setTarget(UUID target) {
        this.target = target;

        //Fetch identifying properties of the target node.
        try(Transaction tx = LogPreprocessor.graphDB.db.beginTx();
            Result result = tx.execute("match (n) where n.id = '%s' return n limit 1".formatted(target.toString()));
            ResourceIterator<Node> resultIt = result.columnAs("n");
        ){
            Node _targetNode = resultIt.next();
            if(_targetNode == null){
                throw new RuntimeException("Could not retrieve target node with id: " + target.toString());
            }

            this.targetMethod = (String)_targetNode.getProperty("method");
            this.targetPath = (String)_targetNode.getProperty("path");

            if(_targetNode.hasProperty("operationName")){
                this.targetOperationName = (String)_targetNode.getProperty("operationName");
            }

        };

        return this;
    }

    public Set<String> getTargets() {
        return targets;
    }

    public ExecutionRequest setTargets(Set<String> targets) {
        this.targets = targets;
        return this;
    }

    public Type getType() {
        return type;
    }

    public ExecutionRequest setType(Type type) {
        this.type = type;
        return this;
    }

    public ExecutionRequest addRecomputation(){
        this.pathRecomputations++;
        return this;
    }

    public int getPathRecomputations() {
        return pathRecomputations;
    }

    public ExecutionRequest setPathRecomputations(int pathRecomputations) {
        this.pathRecomputations = pathRecomputations;
        return this;
    }

    public List<ExecutionParameter> getParameters() {
        return parameters;
    }

    public JsonArray getParameterAsJson(){
        return this.getParameters().stream().map(ExecutionParameter::toJson).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
    }

    public ExecutionRequest addParameter(ExecutionParameter parameter){
        if(this.parameters == null){
            this.parameters = new ArrayList<>();
        }

        this.parameters.add(parameter);
        return this;
    }

    public ExecutionRequest setParameters(List<ExecutionParameter> parameters) {
        log.info("Execution Request Parameters:");
        parameters.forEach(parameter -> log.info(parameter.toJson().encodePrettily()));
        this.parameters = parameters;
        return this;
    }

    public ExecutionParameter getParameter(String id){
        ExecutionParameter result = this.parameters.stream().filter(parameter -> parameter.getNodeId().equals(UUID.fromString(id)))
                .findFirst().orElse(null);

        if(result == null){
            log.error("Could not find parameter with id: {} in list of execution parameters for execution {}",id, getId().toString());
        }

        return result;
    }

    public String getUserLocation() {
        return userLocation;
    }

    public ExecutionRequest setUserLocation(String userLocation) {
        this.userLocation = userLocation;
        return this;
    }

    public String getTargetMethod() {
        return targetMethod;
    }

    public ExecutionRequest setTargetMethod(String targetMethod) {
        this.targetMethod = targetMethod;
        return this;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public ExecutionRequest setTargetPath(String targetPath) {
        this.targetPath = targetPath;
        return this;
    }

    public Set<String> getFailedNodes() {
        return failedNodes;
    }

    public ExecutionRequest addFailedNode(String failedNodeId){
        this.failedNodes.add(failedNodeId);
        return this;
    }

    public ExecutionRequest setFailedNodes(Set<String> failedNodes) {
        this.failedNodes = failedNodes;
        return this;
    }

    public Set<String> getResourceParameters() {
        return resourceParameters;
    }

    public ExecutionRequest setResourceParameters(Set<String> resourceParameters) {
        this.resourceParameters = resourceParameters;
        return this;
    }

    public Set<String> getInputParameters() {
        return inputParameters;
    }

    public ExecutionRequest setInputParameters(Set<String> inputParameters) {
        this.inputParameters = inputParameters;
        return this;
    }

    public Set<String> getApiCalls() {
        return apiCalls;
    }

    public ExecutionRequest setApiCalls(Set<String> apiCalls) {
        this.apiCalls = apiCalls;
        return this;
    }

    public Set<String> getVisitedNodes() {
        if(visitedNodes == null){
            visitedNodes = new HashSet<>();
        }
        return visitedNodes;
    }

    public ExecutionRequest setVisitedNodes(Set<String> visitedNodes) {
        this.visitedNodes = visitedNodes;
        return this;
    }
}


