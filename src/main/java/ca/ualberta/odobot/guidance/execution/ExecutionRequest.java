package ca.ualberta.odobot.guidance.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ExecutionRequest {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRequest.class);

    private UUID id;

    private UUID target; //Target node id.

    private String userLocation;

    private List<ExecutionParameter> parameters;


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
}
