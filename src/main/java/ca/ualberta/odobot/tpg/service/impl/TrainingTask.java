package ca.ualberta.odobot.tpg.service.impl;

import ca.ualberta.odobot.sqlite.impl.TrainingExemplar;
import ca.ualberta.odobot.tpg.TPGAlgorithm;
import ca.ualberta.odobot.tpg.TPGLearn;
import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

public class TrainingTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TrainingTask.class);
    JsonObject config;
    List<TrainingExemplar> dataset;

    Promise<TPGAlgorithm> taskPromise;

    public TrainingTask( Promise<TPGAlgorithm> promise, JsonObject config, List<TrainingExemplar> trainingDataset){
        this.taskPromise = promise;
        this.dataset = trainingDataset;
        this.config = config;
    }


    public void run() {

        log.info("Hello from the other thread");

        TPGAlgorithm tpgAlgorithm = TPGAlgorithm.getInstance(config, null, "learn");

        try{

        TPGLearn tpg = tpgAlgorithm.getTPGLearn();

        log.info("Created TPGAlgorithm and TPGLearn objects");

        long [] urlActions = generateActions(dataset,0);
        long [] requestActions = generateActions(dataset, 1);
        long [] responseActions = generateActions(dataset, 2);

        /*
         *  Set some dummy actions, these won't actually be used unless config.getString("numberofActionRegisters") is "-1".
         */
        tpg.setActions(new long [] {0L, 1L, 2L, 3L, 4L, 5L});

        tpg.initialize();

        double reward = 0.0;

        log.info("Training...");

        log.info("remaining teams: {}", tpg.remainingTeams());

        for (int i = 0; i < config.getInteger("numGenerations"); i++){

            Map<String, Double> generationScoreSummary = new LinkedHashMap<>();
            //Let every team classify
            while (tpg.remainingTeams() > 0){
//                log.info("Team {}", tpg.getCurrTeamID());

                //reset reward to 0 for each team that classifies
                reward = 0.0;

                //Go through the training exemplars
                Iterator<TrainingExemplar> it = dataset.iterator();
                while (it.hasNext()){

                    TrainingExemplar exemplar = it.next();

                    double [] registerArray = tpg.participate(exemplar.featureVector());

                    double [] action = Arrays.copyOf(registerArray, Integer.parseInt(config.getString("numberofActionRegisters")) );

                    long [] predictedLabel = new long [3];
                    predictedLabel[0] = urlActions[(int)Math.floor(Math.abs(action[0]))%urlActions.length];
                    predictedLabel[1] = requestActions[(int)Math.floor(Math.abs(action[1]))%requestActions.length];
                    predictedLabel[2] = responseActions[(int)Math.floor(Math.abs(action[2]))%responseActions.length];


                    reward += score(predictedLabel, exemplar);
                }


                generationScoreSummary.put(Long.toString(tpg.getCurrTeamID()), reward);

//                log.info("Score: {} maxScore:{}", reward, dataset.size()*3);
                tpg.reward(config.getString("trainingTaskName"), reward);

//                log.info("************************************");
//                log.info("remaining teams: {}", tpg.remainingTeams());
//                log.info("************************************");
            }


            List<Map.Entry<String,Double>> generationResults = new ArrayList(generationScoreSummary.entrySet());
            generationResults.sort(Map.Entry.comparingByValue());
            generationResults.forEach(entry->log.info("Team {}\t{}", entry.getKey(), entry.getValue()));


            Supplier<DoubleStream> generationScoresSupplier = ()->generationResults.stream().mapToDouble(entry->entry.getValue());

            double generationAverage = generationScoresSupplier.get().average().getAsDouble();
            double generationMin = generationScoresSupplier.get().min().getAsDouble();
            double generationMax = generationScoresSupplier.get().max().getAsDouble();

            log.info("Generation {} complete. Scores [Min: {}, Avg: {}, Max: {}] Maximum possible score: {}",tpg.getEpochs() , generationMin, generationAverage, generationMax, dataset.size()*3);


            //Perform selection
            tpg.selection();
            tpg.generateNewTeams(config.getLong("mutationRoundsPerGeneration"));
            tpg.nextEpoch();

        }

        taskPromise.complete(tpgAlgorithm);

        }catch (Exception e){
            log.error(e.getMessage(), e);
        }
    }

    private double score(long [] predictedLabel, TrainingExemplar exemplar){
        long [] correctLabel = Arrays.stream(exemplar.labels()).mapToLong(i->(long)i).toArray();

        double score = 0.0;


        if(predictedLabel[0] == correctLabel[0]){
            score += 1.0;
        }
        if(predictedLabel[1] == correctLabel[1]){
            score += 1.0;
        }
        if(predictedLabel[2] == correctLabel[2]){
            score += 1.0;
        }

        return score;
    }

    /**
     * Generate an array of possible actions along a specific dimension of exemplar labels.
     *
     * That is, if we have a labels array of size 3, and there are 6 distinct values labels[0] across all
     * exemplars, then we will generate a long [6] when dimension is set to 0.
     * @param dataset
     * @param dimension
     * @return
     */
    private long [] generateActions(List<TrainingExemplar> dataset, int dimension){

        //Compile a set of unique labels for this dataset.
        return dataset.stream()
                .distinct()
                .map(exemplar->(long)exemplar.labels()[dimension])
                .mapToLong(l->(long)l).toArray();

    }

}
