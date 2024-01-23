package ca.ualberta.odobot.tpg.analysis.metrics;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;

import java.util.Optional;

@DataObject
public class FitnessMetric implements MetricComponent{

    private static final String JSON_PREFIX = "fitness_";
    public String note = "";
    public Optional <Double> minimum  = Optional.empty();
    public Optional<Double> maximum  = Optional.empty();
    public Optional<Double> mean  = Optional.empty();

    public Optional<Double> score = Optional.empty();

    public MetricContext type;

    public Optional<Long> generation  = Optional.empty();
    public Optional<Long> teamId  = Optional.empty();

    public FitnessMetric (){};

    public FitnessMetric(JsonObject data){
        this.note = data.getString(JSON_PREFIX + "note");

        this.maximum = data.containsKey(JSON_PREFIX + "maximum")?
                Optional.of(data.getDouble(JSON_PREFIX + "maximum")):
                Optional.empty()
        ;

        this.minimum = data.containsKey(JSON_PREFIX+"minimum")?
                Optional.of(data.getDouble(JSON_PREFIX + "minimum")):
                Optional.empty()
        ;

        this.mean = data.containsKey(JSON_PREFIX+"mean")?
                Optional.of(data.getDouble(JSON_PREFIX + "mean")):
                Optional.empty()
        ;

        this.score = data.containsKey(JSON_PREFIX + "score")?
                Optional.of(data.getDouble(JSON_PREFIX+"score")):
                Optional.empty()
        ;

        this.type = MetricContext.valueOf(data.getString(JSON_PREFIX + "type"));

        this.generation = data.containsKey(JSON_PREFIX + "generation")?
                Optional.of(data.getLong(JSON_PREFIX+"generation")):
                Optional.empty();

        //Team id should be encoded as a string, as we treat it more like a label in Kibana/Elasticsearch during analysis.
        this.teamId = data.containsKey(JSON_PREFIX + "teamId")?
                Optional.of(Long.parseLong(data.getString(JSON_PREFIX + "teamId"))):
                Optional.empty();
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject()
                .put(JSON_PREFIX + "note", note)
                .put(JSON_PREFIX + "type", type.toString());

        if(maximum.isPresent()){
            result.put(JSON_PREFIX+"maximum", maximum.get());
        }

        if(minimum.isPresent()){
            result.put(JSON_PREFIX+"minimum", minimum.get());
        }

        if(mean.isPresent()){
            result.put(JSON_PREFIX+"mean", mean.get());
        }

        if(score.isPresent()){
            result.put(JSON_PREFIX+"score", score.get());
        }

        if(generation.isPresent()){
            result.put(JSON_PREFIX +"generation", generation.get());
        }

        if(teamId.isPresent()){
            //Team id should be encoded as a string, as we treat it more like a label in Kibana/Elasticsearch during analysis.
            result.put(JSON_PREFIX + "teamId", Long.toString(teamId.get()) );
        }

        return result;
    }

}
