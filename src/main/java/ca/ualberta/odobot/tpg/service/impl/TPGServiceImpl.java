package ca.ualberta.odobot.tpg.service.impl;

import ca.ualberta.odobot.sqlite.impl.TrainingExemplar;
import ca.ualberta.odobot.tpg.TPGAlgorithm;
import ca.ualberta.odobot.tpg.TPGLearn;
import ca.ualberta.odobot.tpg.service.TPGService;
import ca.ualberta.odobot.tpg.util.SaveLoad;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class TPGServiceImpl implements TPGService {

    private static final Logger log = LoggerFactory.getLogger(TPGServiceImpl.class);

    @Override
    public Future<JsonObject> train(JsonObject config, List<JsonObject> data) {

        List<TrainingExemplar> dataset = data.stream().map(o->TrainingExemplar.fromJson(o)).collect(Collectors.toList());

        TPGAlgorithm tpgAlgorithm = TPGAlgorithm.getInstance(config, null, "learn");

        TPGLearn tpg = tpgAlgorithm.getTPGLearn();

        long [] actions = generateActions(dataset);

        tpg.setActions(actions);

        tpg.initialize();

        double reward = 0.0;

        log.info("Training...");

        log.info("remaining teams: {}", tpg.remainingTeams());

        for (int i = 0; i < config.getInteger("numGenerations"); i++){


            //Let every team classify
            while (tpg.remainingTeams() > 0){
                log.info("Team {}", tpg.getCurrTeamID());

                //reset reward to 0 for each team that classifies
                reward = 0.0;

                //Go through the training exemplars
                Iterator<TrainingExemplar> it = dataset.iterator();
                while (it.hasNext()){

                    TrainingExemplar exemplar = it.next();

                    double [] registerArray = tpg.participate(exemplar.featureVector());

                    double action = registerArray[registerArray.length-1];

                    long predictedLabel = actions[(int)Math.floor(Math.abs(action))%actions.length];

                    reward += score(predictedLabel, exemplar);
                }

                log.info("Score: {} datasetSize:{}", reward, dataset.size());
                tpg.reward(config.getString("trainingTaskName"), reward);

                log.info("************************************");
                log.info("remaining teams: {}", tpg.remainingTeams());
                log.info("************************************");
            }

            log.info("Generation " + tpg.getEpochs() + " complete.");

            //Perform selection
            tpg.selection();
            tpg.generateNewTeams(config.getLong("mutationRoundsPerGeneration"));
            tpg.nextEpoch();

        }




        return Future.succeededFuture(new JsonObject());
    }

    private double score(long predictedLabel, TrainingExemplar exemplar){
        if(predictedLabel == (long)exemplar.label()){
            return 1.0;
        }else{
            return 0.0;
        }
    }

    private long [] generateActions(List<TrainingExemplar> dataset){

        //Compile a set of unique labels for this dataset.
        Set<Long> uniqueLabels = dataset.stream()
                .map(exemplar->(long)exemplar.label())
                .collect(Collectors.toSet());

        return uniqueLabels.stream().mapToLong(label->label).toArray();
    }
}
