package ca.ualberta.odobot.guidance.execution;

import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ExecutionRequest {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRequest.class);

    public enum Type{
        PREDEFINED, NL
    }

    private Type type;
    private UUID id;

    private UUID target; //Target node id.

    private Set<String> targets;

    private String userLocation;

    private List<ExecutionParameter> parameters;

    private String targetMethod;
    private String targetPath;

    public UUID getId() {
        return id;
    }

    public ExecutionRequest setId(UUID id) {
        this.id = id;
        return this;
    }

    public UUID getTarget() {
        return target;
    }

    public ExecutionRequest setTarget(UUID target) {
        this.target = target;

        //Fetch identifying properties of the target node.
        try(Transaction tx = LogPreprocessor.graphDB.db.beginTx();
            Result result = tx.execute("match (n:APINode) where n.id = '%s' return n limit 1".formatted(target.toString()));
            ResourceIterator<Node> resultIt = result.columnAs("n");
        ){
            Node _targetNode = resultIt.next();
            if(_targetNode == null){
                throw new RuntimeException("Could not retrieve target node with id: " + target.toString());
            }

            this.targetMethod = (String)_targetNode.getProperty("method");
            this.targetPath = (String)_targetNode.getProperty("path");
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

    public List<ExecutionParameter> getParameters() {
        return parameters;
    }

    public ExecutionRequest addParameter(ExecutionParameter parameter){
        if(this.parameters == null){
            this.parameters = new ArrayList<>();
        }

        this.parameters.add(parameter);
        return this;
    }

    public ExecutionRequest setParameters(List<ExecutionParameter> parameters) {
        this.parameters = parameters;
        return this;
    }

    public ExecutionParameter getParameter(String id){
        ExecutionParameter result = this.parameters.stream().filter(parameter -> parameter.getNodeId().equals(UUID.fromString(id)))
                .findFirst().get();

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
}
