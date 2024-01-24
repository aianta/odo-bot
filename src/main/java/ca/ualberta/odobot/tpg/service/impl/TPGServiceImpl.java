package ca.ualberta.odobot.tpg.service.impl;

import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.sqlite.impl.TrainingExemplar;
import ca.ualberta.odobot.tpg.TPGAlgorithm;
import ca.ualberta.odobot.tpg.TPGLearn;
import ca.ualberta.odobot.tpg.service.TPGService;
import ca.ualberta.odobot.tpg.teams.Team;
import ca.ualberta.odobot.tpg.util.SaveLoad;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class TPGServiceImpl implements TPGService {

    private static final Logger log = LoggerFactory.getLogger(TPGServiceImpl.class);

    private SaveLoad saveLoad;

    private ElasticsearchService elasticsearchService;

    public TPGServiceImpl(ElasticsearchService elasticsearchService){
        this.elasticsearchService = elasticsearchService;
        this.saveLoad = new SaveLoad();

    }

    @Override
    public Future<JsonObject> train(JsonObject config, JsonArray data) {
        Promise<TPGAlgorithm> promise = Promise.promise();
        List<TrainingExemplar> dataset = data.stream().map(o->TrainingExemplar.fromJson((JsonObject)o)).collect(Collectors.toList());

        //Some extremely basic validation
        if(config.getInteger("testSamplesPerLabel") >= config.getInteger("samplesPerLabel")){
            log.error("testSamplesPerLabel ({}) is greater or equal to the number of samplesPerLabel({}) ",config.getInteger("testSamplesPerLabel"),config.getInteger("samplesPerLabel")  );
            throw new RuntimeException("Invalid training task configuration!");
        }

        //Need to capture this before getTestData() as that method will remove test exemplars from the original dataset.
        int originalDatasetSize = dataset.size();

        Collections.shuffle(dataset);

        //Balance & Split dataset
        //NOTE: The exemplars in the test dataset will be removed from the training dataset, and by reference also the dataset.
        //Hence why originalDatasetSize is captured beforehand for metrics.
        List<TrainingExemplar> trainingDataset = balanceDataset(dataset, config.getInteger("samplesPerLabel"));
        List<TrainingExemplar> testData = getTestData(trainingDataset, config.getInteger("testSamplesPerLabel"));





        log.info("Starting training task on a new thread");
        /**
         * For more details about using a callable with the Thread class see:
         * https://stackoverflow.com/questions/25231149/can-i-use-callable-threads-without-executorservice
         */
        TrainingTaskImpl task = new TrainingTaskImpl( promise, config, elasticsearchService, dataset);
        Thread thread = new Thread(task);
        thread.start();

        return promise.future().compose(tpgAlgorithm -> saveChampion(tpgAlgorithm, task.getRunMetric().id));
    }

    @Override
    public Future<Void> identify(JsonObject config, JsonObject exemplarJson) {
        TrainingExemplar exemplar = TrainingExemplar.fromJson(exemplarJson);

        String championPath = config.getString("championPath");
        try{
            Team champion  = saveLoad.loadTeam(championPath);

            double [] registerArray = champion.getAction(new HashSet<>(), exemplar.featureVector());

            log.info("Champion produced register array: {}", registerArray);
        }catch (IOException ioe){
            log.error(ioe.getMessage(), ioe);
            return Future.failedFuture(ioe);
        }


        return Future.succeededFuture();
    }

    private Future<JsonObject> saveChampion(TPGAlgorithm trainedTPG, UUID runID){

        try{
        JsonObject championPaths = new JsonObject();

        TPGLearn tpgLearn = trainedTPG.getTPGLearn();
        Iterator<Team> it = tpgLearn.rootTeams.iterator();
        while (it.hasNext()){
            Team champion = it.next();
            String championPath = saveLoad.saveTeam(champion, tpgLearn.getEpochs(), 0, "./tpg/champions/" + runID.toString() + "/");
            championPaths.put(Long.toString(champion.ID), championPath);
        }





        return Future.succeededFuture(championPaths);
        }catch (Exception e){
            log.error(e.getMessage(), e);
            return Future.failedFuture(e);
        }
    }

    /**
     * Takes a list of training exemplars and removes a target number of samples per label into a
     * test dataset. The training exemplars selected for the test dataset are removed from the input
     * dataset.
     * @param dataset the input dataset, should already be balanced. See {@link #balanceDataset(List, int)}.
     * @param samplesPerLabel the target number of samples per label in the test dataset
     * @return the test dataset
     */
    private List<TrainingExemplar> getTestData(List<TrainingExemplar> dataset, int samplesPerLabel){

        List<TrainingExemplar> testData = new ArrayList<>();
        Map<Integer,Integer> distribution = new HashMap<>();

        Collections.shuffle(dataset);
        Iterator<TrainingExemplar> it = dataset.iterator();

        while (it.hasNext()){
            TrainingExemplar exemplar = it.next();
            int label = exemplar.labels()[0];
            int count = distribution.getOrDefault(label, 0);
            if(count < samplesPerLabel){
                testData.add(exemplar);
                it.remove();
            }
            distribution.put(label, count + 1);

        }

        return testData;

    }

    /**
     *
     * @param dataset input dataset with varying numbers of samples per label
     * @param samplesPerLabel the ideal number of samples per label
     * @return a dataset that contains the desired number of samples per label
     */
    private List<TrainingExemplar> balanceDataset(List<TrainingExemplar> dataset, int samplesPerLabel){

        List<TrainingExemplar> result = new ArrayList<>();

        Map<Integer, Integer> distribution = new LinkedHashMap<>();
        dataset.forEach(exemplar -> {
            int count = distribution.getOrDefault(exemplar.labels()[0], 0);
            distribution.put(exemplar.labels()[0], count+1);
        });


        Map<Integer, Integer> toAdd = new HashMap<>();
        distribution.entrySet().stream().filter(entry->entry.getValue()>=samplesPerLabel).forEach(entry->toAdd.put(entry.getKey(), samplesPerLabel));

        Collections.shuffle(dataset);

        Iterator<TrainingExemplar> iterator = dataset.iterator();
        while (iterator.hasNext()){
            TrainingExemplar exemplar = iterator.next();
            int label = exemplar.labels()[0];
            if(toAdd.get(label) != null && toAdd.get(label) > 0){
                result.add(exemplar);
                toAdd.put(label, toAdd.get(label) - 1);
            }
        }

        Map<Integer,Integer> finalDistribution = new LinkedHashMap<>();
        result.forEach(exemplar -> {
            int count = finalDistribution.getOrDefault(exemplar.labels()[0], 0);
            finalDistribution.put(exemplar.labels()[0], count+1);
        });

        finalDistribution.entrySet().stream().forEach(entry->log.info("{}:{}", entry.getKey(), entry.getValue()));

        return result;
    }


}
