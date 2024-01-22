package ca.ualberta.odobot.tpg.analysis.metrics;

import ca.ualberta.odobot.tpg.TPGLearn;
import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;

/**
 * @author Alexandru Ianta
 * Holder of parameter metrics for a TPG instance. Used for analysis.
 */
@DataObject
public class ParametersMetric implements MetricComponent{

    private static final String JSON_PREFIX = "param_";

    int teamPopSize;
    double teamGap;
    double probLearnerDelete;
    double probLearnerAdd;
    double probMutateAction;
    double probActionIsTeam;
    int maximumTeamSize;
    int maximumProgramSize;
    double probProgramDelete;
    double probProgramAdd;
    double probProgramSwap;
    double probProgramMutate;
    int maximumActionProgramSize;
    double probActionProgramDelete;
    double probActionProgramAdd;
    double probActionProgramSwap;
    double probActionProgramMutate;
    int numberOfActionRegisters;
    int seed;

    public static ParametersMetric of(TPGLearn tpgLearn){
        ParametersMetric result = new ParametersMetric();
        result.teamPopSize = tpgLearn.teamPopSize;
        result.teamGap = tpgLearn.teamGap;
        result.probLearnerDelete = tpgLearn.probLearnerDelete;
        result.probLearnerAdd = tpgLearn.probLearnerAdd;
        result.probMutateAction = tpgLearn.probMutateAction;
        result.probActionIsTeam = tpgLearn.probActionIsTeam;
        result.maximumTeamSize = tpgLearn.maximumTeamSize;
        result.maximumProgramSize = tpgLearn.maximumProgramSize;
        result.probProgramDelete = tpgLearn.probProgramDelete;
        result.probProgramAdd = tpgLearn.probProgramAdd;
        result.probProgramSwap = tpgLearn.probProgramSwap;
        result.probProgramMutate = tpgLearn.probProgramMutate;
        result.maximumActionProgramSize = tpgLearn.maximumActionProgramSize;
        result.probActionProgramDelete = tpgLearn.probActionProgramDelete;
        result.probActionProgramAdd = tpgLearn.probActionProgramAdd;
        result.probActionProgramSwap = tpgLearn.probActionProgramSwap;
        result.probActionProgramMutate = tpgLearn.probActionProgramMutate;
        result.numberOfActionRegisters = tpgLearn.numberofActionRegisters;
        result.seed = tpgLearn.getSeed();
        return result;
    }

    public ParametersMetric() {};
    public ParametersMetric(JsonObject data){
        this.teamPopSize = data.getInteger(JSON_PREFIX + "teamPopSize");
        this.teamGap = data.getDouble(JSON_PREFIX + "teamGap");
        this.probLearnerDelete = data.getDouble(JSON_PREFIX + "probLearnerDelete");
        this.probLearnerAdd = data.getDouble(JSON_PREFIX + "probLearnerAdd");
        this.probMutateAction = data.getDouble(JSON_PREFIX + "probMutateAction");
        this.probActionIsTeam = data.getDouble(JSON_PREFIX + "probActionIsTeam");
        this.maximumTeamSize = data.getInteger(JSON_PREFIX + "maximumTeamSize");
        this.maximumProgramSize = data.getInteger(JSON_PREFIX + "maximumProgramSize");
        this.probProgramDelete = data.getDouble(JSON_PREFIX + "probProgramDelete");
        this.probProgramAdd = data.getDouble(JSON_PREFIX + "probProgramAdd");
        this.probProgramSwap = data.getDouble(JSON_PREFIX + "probProgramSwap");
        this.probProgramMutate = data.getDouble(JSON_PREFIX + "probProgramMutate");
        this.maximumActionProgramSize = data.getInteger(JSON_PREFIX + "maximumActionProgramSize");
        this.probActionProgramDelete = data.getDouble(JSON_PREFIX + "probActionProgramDelete");
        this.probActionProgramAdd = data.getDouble(JSON_PREFIX + "probActionProgramAdd");
        this.probActionProgramSwap = data.getDouble(JSON_PREFIX + "probActionProgramSwap");
        this.probActionProgramMutate = data.getDouble(JSON_PREFIX + "probActionProgramMutate");
        this.numberOfActionRegisters = data.getInteger(JSON_PREFIX + "numberOfActionRegisters");
        this.seed = data.getInteger(JSON_PREFIX + "seed");

    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject()
                .put(JSON_PREFIX + "teamPopSize", teamPopSize)
                .put(JSON_PREFIX + "teamGap", teamGap)
                .put(JSON_PREFIX + "probLearnerDelete", probLearnerDelete)
                .put(JSON_PREFIX + "probLearnerAdd", probLearnerAdd)
                .put(JSON_PREFIX + "probMutateAction", probMutateAction)
                .put(JSON_PREFIX + "probActionIsTeam", probActionIsTeam)
                .put(JSON_PREFIX + "maximumTeamSize", maximumTeamSize)
                .put(JSON_PREFIX + "maximumProgramSize", maximumProgramSize)
                .put(JSON_PREFIX + "probProgramDelete", probProgramDelete)
                .put(JSON_PREFIX + "probProgramAdd", probProgramAdd)
                .put(JSON_PREFIX + "probProgramSwap", probProgramSwap)
                .put(JSON_PREFIX + "probProgramMutate", probProgramMutate)
                .put(JSON_PREFIX + "maximumActionProgramSize", maximumActionProgramSize)
                .put(JSON_PREFIX + "probActionProgramDelete", probActionProgramDelete)
                .put(JSON_PREFIX + "probActionProgramAdd", probActionProgramAdd)
                .put(JSON_PREFIX + "probActionProgramSwap", probActionProgramSwap)
                .put(JSON_PREFIX + "probActionProgramMutate", probActionProgramMutate)
                .put(JSON_PREFIX + "numberOfActionRegisters", numberOfActionRegisters)
                .put(JSON_PREFIX + "seed", seed);

        return result;
    }


}
