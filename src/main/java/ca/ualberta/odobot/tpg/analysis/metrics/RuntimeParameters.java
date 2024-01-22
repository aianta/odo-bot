package ca.ualberta.odobot.tpg.analysis.metrics;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;
import org.apache.zookeeper.Op;

import java.util.Optional;

@DataObject
public class RuntimeParameters implements MetricComponent{

    private static final String JSON_PREFIX = "runtime_param_";

    public int numRootTeams;

    public int numLearners;

    public Optional<Long> generation  = Optional.empty();

    public RuntimeParameters(){}

    public RuntimeParameters(JsonObject data){
        this.numRootTeams = data.getInteger(JSON_PREFIX + "numRootTeams");
        this.numLearners = data.getInteger(JSON_PREFIX  + "numLearners");

        this.generation = data.containsKey(JSON_PREFIX+"generation")?
                Optional.of(data.getLong(JSON_PREFIX+"generation")):
                Optional.empty();

    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject()
                .put(JSON_PREFIX + "numRootTeams", numRootTeams)
                .put(JSON_PREFIX + "numLearners", numLearners);

        if(generation.isPresent()){
            result.put(JSON_PREFIX+"generation", generation.get());
        }

        return result;
    }

}
