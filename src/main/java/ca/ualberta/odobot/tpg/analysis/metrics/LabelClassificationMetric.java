package ca.ualberta.odobot.tpg.analysis.metrics;

import io.vertx.core.json.JsonObject;

import java.util.Optional;

public class LabelClassificationMetric implements MetricComponent{

    private static final String JSON_PREFIX = "classification_metric_";
    public String humanLabel;

    public int label;
    int correct = 0;
    int incorrect = 0;
    int total  = 0;

    public MetricContext context;

    public Optional<Long> teamId = Optional.empty();

    Optional<Integer> generation = Optional.empty();

    public LabelClassificationMetric(String humanLabel, int label){
        this.humanLabel = humanLabel;
        this.label = label;
    }

    public LabelClassificationMetric(){}

    public LabelClassificationMetric addCorrect(){
        correct+=1;
        total+=1;
        return this;
    }

    public LabelClassificationMetric addIncorrect(){
        incorrect+=1;
        total+=1;
        return this;
    }

    public LabelClassificationMetric(JsonObject data){
        this.humanLabel = data.getString(JSON_PREFIX+"humanLabel");
        this.label = data.getInteger(JSON_PREFIX+"label");
        this.correct = data.getInteger(JSON_PREFIX+"correct");
        this.incorrect = data.getInteger(JSON_PREFIX+"incorrect");
        this.total = data.getInteger(JSON_PREFIX+"total");
        this.context = MetricContext.valueOf(data.getString(JSON_PREFIX+"context"));

        //Team id should be encoded as a string, as we treat it more like a label in Kibana/Elasticsearch during analysis.
        if(data.containsKey(JSON_PREFIX + "teamId")){
            this.teamId = Optional.of(Long.parseLong(data.getString(JSON_PREFIX+"teamId")));
        }

        if(data.containsKey(JSON_PREFIX+"generation")){
            this.generation = Optional.of(data.getInteger(JSON_PREFIX+"generation"));
        }
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject()
                .put(JSON_PREFIX + "humanLabel", humanLabel)
                .put(JSON_PREFIX + "label", label)
                .put(JSON_PREFIX + "correct", correct)
                .put(JSON_PREFIX + "incorrect", incorrect)
                .put(JSON_PREFIX + "total", total)
                .put(JSON_PREFIX + "context", context.name());

        if(teamId.isPresent()){
            //Team id should be encoded as a string, as we treat it more like a label in Kibana/Elasticsearch during analysis.
            result.put(JSON_PREFIX + "teamId", Long.toString(teamId.get()));
        }

        if(generation.isPresent()){
            result.put(JSON_PREFIX+"generation", generation);
        }
        return result;
    }

}
