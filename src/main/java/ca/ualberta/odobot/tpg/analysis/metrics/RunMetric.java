package ca.ualberta.odobot.tpg.analysis.metrics;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;

import java.util.Optional;
import java.util.UUID;

@DataObject
public class RunMetric implements MetricComponent{

    private static final String JSON_PREFIX = "run_";

    public UUID id;
    public String name;
    public Optional<String> description = Optional.empty();

    public String taskName;

    public long numGenerations;

    public Optional<Long> mutationRoundsPerGeneration;

    public RunMetric(){
        id = UUID.randomUUID();
    }

    public RunMetric(JsonObject data){
        this.id = UUID.fromString(data.getString(JSON_PREFIX+"id"));
        this.name = data.getString(JSON_PREFIX+"name");
        this.taskName = data.getString(JSON_PREFIX+"taskName");
        this.numGenerations = data.getLong(JSON_PREFIX+"numGenerations");

        this.mutationRoundsPerGeneration = data.containsKey(JSON_PREFIX+"mutationRoundsPerGeneration")?
                Optional.of(data.getLong(JSON_PREFIX+"mutationRoundsPerGeneration")):
                Optional.empty();

        this.description = data.containsKey(JSON_PREFIX+"description")?
                Optional.of(data.getString(JSON_PREFIX+"description")):
                Optional.empty();

    }


    public JsonObject toJson(){
        JsonObject result = new JsonObject()
                .put(JSON_PREFIX+"id", id.toString())
                .put(JSON_PREFIX+"name", name)
                .put(JSON_PREFIX+"taskName", taskName)
                .put(JSON_PREFIX+"numGenerations", numGenerations);

        if(mutationRoundsPerGeneration.isPresent()){
            result.put(JSON_PREFIX+"mutationRoundsPerGeneration", mutationRoundsPerGeneration.get());
        }

        if(description.isPresent()){
            result.put(JSON_PREFIX+"description", description.get());
        }

        return result;
    }

}
