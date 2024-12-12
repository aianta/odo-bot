package ca.ualberta.odobot.guidance.execution;

import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class ExecutionParameter {

    private static final Logger log = LoggerFactory.getLogger(ExecutionParameter.class);

    public enum ParameterType{
        InputParameter, SchemaParameter
    }

    public static ExecutionParameter fromJson(JsonObject json){
        String type = json.getString("type");

        ExecutionParameter result = switch (type){
            case "InputParameter" -> {
                InputParameter param = new InputParameter();
                param.setType(ParameterType.InputParameter);
                param.setValue(json.getString("value"));
                yield param;
            }
            case "SchemaParameter" ->{
                SchemaParameter param = new SchemaParameter();
                param.setType(ParameterType.SchemaParameter);
                param.setQuery(json.getString("query"));
                yield param;
            }
            default -> {
                log.error("Unrecognized parameter type!");
                yield null;
            }
        };

        result.setNodeId(UUID.fromString(json.getString("id")));

        return result;
    }

    protected UUID nodeId;
    protected ParameterType type;

    public UUID getNodeId() {
        return nodeId;
    }

    public ExecutionParameter setNodeId(UUID nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    public ParameterType getType() {
        return type;
    }

    public ExecutionParameter setType(ParameterType type) {
        this.type = type;
        return this;
    }
}
