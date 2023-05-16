package ca.ualberta.odobot.logpreprocessor.executions.impl;

import ca.ualberta.odobot.logpreprocessor.executions.PreprocessingPipelineExecutionStatus;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Define some status classes for preprocessing pipeline executions
 */
public abstract class AbstractPreprocessingPipelineExecutionStatus implements PreprocessingPipelineExecutionStatus {
    private static final Logger log = LoggerFactory.getLogger(AbstractPreprocessingPipelineExecutionStatus.class);

    public static AbstractPreprocessingPipelineExecutionStatus fromJson(JsonObject input){
        return switch (input.getString("status")){
            case "Failed" -> new Failed(input);
            case "In progress" -> new InProgress(input);
            case "Initialized" -> new Initialized(input);
            case "Complete"-> new Complete();
            default -> null;
        };
    }

    public static class Failed extends AbstractPreprocessingPipelineExecutionStatus{
        public Failed(){
            super("Failed");
        }

        public Failed(JsonObject data){
            super("Failed", data);
        }
    }

    public static class InProgress extends AbstractPreprocessingPipelineExecutionStatus{
        public InProgress(){
            super("In progress");
        }

        public InProgress(JsonObject data){
            super("In progress", data);
        }
    }

    public static class Initialized extends AbstractPreprocessingPipelineExecutionStatus{
        public Initialized(){
            super("Initialized");
        }

        public Initialized(JsonObject data){
            super("Initialized", data);
        }
    }

    public static class Complete extends AbstractPreprocessingPipelineExecutionStatus{
        public Complete(){
            super("Complete");
        }

        public Complete(JsonObject data){
            super("Complete", data);
        }
    }

    String name;

    JsonObject data = new JsonObject();

    public AbstractPreprocessingPipelineExecutionStatus(){}

    public AbstractPreprocessingPipelineExecutionStatus(String name){
        this.name = name;
    }

    public AbstractPreprocessingPipelineExecutionStatus(String name, JsonObject data){
        this.name = name;
        this.data = data;
    }

    public JsonObject data(){
        return this.data;
    }

    public AbstractPreprocessingPipelineExecutionStatus setData(JsonObject data){
        this.data = data;
        return this;
    }

    public String name() {
        return name;
    }

    public AbstractPreprocessingPipelineExecutionStatus setName(String name) {
        this.name = name;
        return this;
    }

    public JsonObject toJson(){
        if (!data.containsKey("status")||!data.getString("status").equals(name())){
            data.put("status", name());
        }
        return data;
    }
}
