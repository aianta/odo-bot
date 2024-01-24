package ca.ualberta.odobot.tpg.analysis;

import ca.ualberta.odobot.tpg.TPGLearn;
import ca.ualberta.odobot.tpg.analysis.metrics.DatasetMetric;
import ca.ualberta.odobot.tpg.analysis.metrics.ParametersMetric;
import ca.ualberta.odobot.tpg.analysis.metrics.RunMetric;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class RunAnalytics {

    private static final Logger log = LoggerFactory.getLogger(RunAnalytics.class);

    private JsonObject config;

    private RunMetric runMetric;

    private DatasetMetric datasetMetric;

    private ParametersMetric parametersMetric;

    public RunAnalytics(JsonObject config){
        this.config = config;

        //Initialize Run Metric
        runMetric = new RunMetric();
        runMetric.name = config.containsKey("runName")?config.getString("runName"):"untitled run " + runMetric.id.toString();
        runMetric.mutationRoundsPerGeneration = Optional.of(config.getLong("mutationRoundsPerGeneration"));
        runMetric.numGenerations = config.getInteger("numGenerations");
        runMetric.taskName = config.getString("trainingTaskName");
        runMetric.description = config.containsKey("runDescription")?Optional.of(config.getString("runDescription")):Optional.empty();
    }



    public void captureParameters(TPGLearn tpg){
        parametersMetric = ParametersMetric.of(tpg);
    }


}
