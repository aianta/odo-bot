package ca.ualberta.odobot.logpreprocessor.executions.impl;

import ca.ualberta.odobot.logpreprocessor.executions.PreprocessingPipelineExecutionStatus;
import io.vertx.core.json.JsonObject;

/**
 * Define some status classes for preprocessing pipeline executions
 */
public abstract class AbstractPreprocessingPipelineExecutionStatus implements PreprocessingPipelineExecutionStatus {

    public class Failed extends AbstractPreprocessingPipelineExecutionStatus{
        public Failed(){
            super("Failed");
        }

        public Failed(JsonObject data){
            super("Failed", data);
        }
    }

    public class InProgress extends AbstractPreprocessingPipelineExecutionStatus{
        public InProgress(){
            super("In progress");
        }

        public InProgress(JsonObject data){
            super("In progress", data);
        }
    }

    public class Initialized extends AbstractPreprocessingPipelineExecutionStatus{
        public Initialized(){
            super("Initialized");
        }

        public Initialized(JsonObject data){
            super("Initialized", data);
        }
    }

    public class Complete extends AbstractPreprocessingPipelineExecutionStatus{
        public Complete(){
            super("Complete");
        }

        public Complete(JsonObject data){
            super("Complete", data);
        }
    }

    String name;

    JsonObject data;

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
        if (!data.containsKey("status")){
            data.put("status", name());
        }
        return data;
    }
}
