package ca.ualberta.odobot.tpg.service;

import ca.ualberta.odobot.sqlite.impl.TrainingExemplar;
import ca.ualberta.odobot.tpg.TPGAlgorithm;
import ca.ualberta.odobot.tpg.TPGLearn;
import ca.ualberta.odobot.tpg.service.impl.Prediction;
import ca.ualberta.odobot.tpg.teams.Team;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @Author Alexandru Ianta
 * @date Jan 24, 2024
 *
 *
 *
 */

public abstract class AbstractTrainingTask implements Runnable{

    private static final Logger log = LoggerFactory.getLogger(AbstractTrainingTask.class);
    protected JsonObject config;

    protected List<TrainingExemplar> dataset;

    protected Promise<TPGAlgorithm> taskPromise;

    protected long [] actions;

    protected Supplier<FitnessStrategy> fitnessStrategySupplier;

    protected Consumer<TPGLearn> beforeTask;
    protected Consumer<Prediction> onPredict;

    protected BiConsumer<Integer, ArrayList<Team>> onGenerationComplete;

    protected FitnessStrategy fitnessStrategy;


    public AbstractTrainingTask(Promise<TPGAlgorithm> promise, JsonObject config, List<TrainingExemplar> dataset, Supplier<FitnessStrategy> fitnessStrategySupplier){
        this.taskPromise = promise;
        this.dataset = dataset;
        this.config = config;
        this.fitnessStrategySupplier = fitnessStrategySupplier;

        actions = computeActions(this.dataset);

        log.info("{} unique labels in training dataset.", actions.length);
    }

    public abstract void run();


    private long [] computeActions(List<TrainingExemplar> dataset){
        //Compile a set of unique labels for this dataset.
        return dataset.stream()
                .mapToLong(exemplar->exemplar.getLabel())
                .distinct()
                .toArray();
    }

}
