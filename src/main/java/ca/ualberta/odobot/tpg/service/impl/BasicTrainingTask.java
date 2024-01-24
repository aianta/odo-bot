package ca.ualberta.odobot.tpg.service.impl;

import ca.ualberta.odobot.sqlite.impl.TrainingExemplar;
import ca.ualberta.odobot.tpg.TPGAlgorithm;
import ca.ualberta.odobot.tpg.TPGLearn;
import ca.ualberta.odobot.tpg.service.AbstractTrainingTask;
import ca.ualberta.odobot.tpg.service.FitnessStrategy;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

public class BasicTrainingTask extends AbstractTrainingTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(BasicTrainingTask.class);

    public BasicTrainingTask(Promise<TPGAlgorithm> promise, JsonObject config, List<TrainingExemplar> dataset, Supplier<FitnessStrategy> fitnessStrategySupplier) {
        super(promise, config, dataset, fitnessStrategySupplier);
    }


    @Override
    public void run() {

        log.info("Starting training using {}", getClass().getSimpleName());

        TPGAlgorithm tpgAlgorithm = TPGAlgorithm.getInstance(config, null, "learn");

        try{

            TPGLearn tpg = tpgAlgorithm.getTPGLearn();
            /*
             *  Set some dummy actions, these won't actually be used unless config.getString("numberofActionRegisters") is "-1".
             */
            tpg.setActions(actions);
            tpg.initialize();

            //Call before task handler
            beforeTask.accept(tpg);

            //Start the training task
            for (int gen = 0; gen < config.getInteger("numGenerations"); gen++){

                while(tpg.remainingTeams() > 0){

                    /**
                     * Fitness strategies are stateful, and thus a new one must be fetched for each
                     * team during a training generation.
                     */
                    fitnessStrategy = fitnessStrategySupplier.get();

                    Iterator<TrainingExemplar> datasetIterator = dataset.iterator();
                    while (datasetIterator.hasNext()){

                        TrainingExemplar exemplar = datasetIterator.next();

                        //Pass the feature vector from the training exemplar to TPG, it will produce register array
                        double [] registerArray = tpg.participate(exemplar.featureVector());

                        //Map the output from TPG to a label by modding the first register value into a valid index of the actions array
                        long predictedLabel = actions[(int) Math.floor(Math.abs(registerArray[0])) % actions.length];

                        //Assemble the predicted label, the training exemplar and the current teamID into a package
                        Prediction p = new Prediction(predictedLabel, exemplar, tpg.getCurrTeamID());

                        //Pass this package to our fitness strategy
                        fitnessStrategy.onPrediction(p);

                        //And to the onPredict handler
                        onPredict.accept(p);
                    }

                    double reward = fitnessStrategy.getReward();
                    tpg.reward(config.getString("trainingTaskName"), reward);

                    //Explicitly mark fitness strategy for garbage collection by setting it to null
                    fitnessStrategy = null;
                }

                onGenerationComplete.accept(gen, tpg.rootTeams);

                //Perform selection
                tpg.selection();
                //Perform generation
                tpg.generateNewTeams(config.getLong("mutationRoundsPerGeneration"));
                //Advance to the next generation
                tpg.nextEpoch();
            }

            log.info("{} finished training!", getClass().getSimpleName());

        }catch (Exception e){
            log.error("Error while executing training task!");
            log.error(e.getMessage(), e);
        }

        taskPromise.complete(tpgAlgorithm);
    }
}
