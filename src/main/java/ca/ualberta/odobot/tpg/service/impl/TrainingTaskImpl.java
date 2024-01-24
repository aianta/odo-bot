package ca.ualberta.odobot.tpg.service.impl;

import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.sqlite.impl.TrainingExemplar;
import ca.ualberta.odobot.tpg.TPGAlgorithm;
import ca.ualberta.odobot.tpg.TPGLearn;
import ca.ualberta.odobot.tpg.analysis.metrics.*;
import ca.ualberta.odobot.tpg.teams.Team;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

public class TrainingTaskImpl implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TrainingTaskImpl.class);
    private static final String ES_INDEX_RUNS = "tpg-service-training-runs";
    private static final String ES_INDEX_TRAINING_FITNESS = "tpg-service-training-fitness";
    private static final String ES_INDEX_CHAMPION_TEST_FITNESS = "tpg-service-champions-test-fitness";

    private static final String ES_INDEX_LABEL_CLASSIFICATION = "tpg-service-label-classifications";
    JsonObject config;
    List<TrainingExemplar> dataset;

    ElasticsearchService elasticsearchService;

    List<TrainingExemplar> trainingData = new ArrayList<>();
    List<TrainingExemplar> testData = new ArrayList<>();
    Promise<TPGAlgorithm> taskPromise;


    public RunMetric getRunMetric() {
        return runMetric;
    }

    //Analytics
    RunMetric runMetric;
    DatasetMetric datasetMetric;



    public TrainingTaskImpl(Promise<TPGAlgorithm> promise, JsonObject config, ElasticsearchService elasticsearchService, List<TrainingExemplar> trainingDataset){
        this.taskPromise = promise;
        this.dataset = trainingDataset;
        this.config = config;
        this.elasticsearchService = elasticsearchService;

        int originalDatasetSize = trainingDataset.size();

        Collections.shuffle(this.dataset);
        this.dataset = balanceDataset(this.dataset, config.getInteger("samplesPerLabel"));
        int balancedDatasetSize = this.dataset.size();

        this.testData = getTestData(this.dataset, config.getInteger("testSamplesPerLabel"));
        this.trainingData = this.dataset;

        log.info("Original dataset size: {} trainingData size: {} testData size: {}", trainingDataset.size(), trainingData.size(), testData.size());

        //Initialize Run Metric
        runMetric = new RunMetric();
        runMetric.name = config.containsKey("runName")?config.getString("runName"):"untitled run " + runMetric.id.toString();
        runMetric.mutationRoundsPerGeneration = Optional.of(config.getLong("mutationRoundsPerGeneration"));
        runMetric.numGenerations = config.getInteger("numGenerations");
        runMetric.taskName = config.getString("trainingTaskName");
        runMetric.description = config.containsKey("runDescription")?Optional.of(config.getString("runDescription")):Optional.empty();

        //Initialize the Dataset Metric
        datasetMetric = new DatasetMetric();
        datasetMetric.numberOfSamplesPerLabelInTrainingDataset = config.getInteger("samplesPerLabel") - config.getInteger("testSamplesPerLabel");
        datasetMetric.numberOfSamplesPerLabelInTestDataset = config.getInteger("testSamplesPerLabel");
        datasetMetric.numberOfSamplesPerLabelInBalancedDataset = config.getInteger("samplesPerLabel");
        datasetMetric.trainingDatasetSize = this.trainingData.size();
        datasetMetric.testDatasetSize = this.testData.size();
        datasetMetric.balancedTotalDatasetSize = balancedDatasetSize;
        datasetMetric.numberOfInputTrainingExemplars = originalDatasetSize;



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


    public void run() {

        log.info("Hello from the other thread");

        TPGAlgorithm tpgAlgorithm = TPGAlgorithm.getInstance(config, null, "learn");

        try{

        TPGLearn tpg = tpgAlgorithm.getTPGLearn();

        log.info("Created TPGAlgorithm and TPGLearn objects");

        long [] pathActions = generateActions(dataset,0);
        long [] requestActions = generateActions(dataset, 1);
        long [] responseActions = generateActions(dataset, 2);

        log.info("{} pathActions", pathActions.length );
        log.info("{} requestActions", requestActions.length);
        log.info("{} responseActions", responseActions.length);

        boolean focusOnOneLabel = config.getBoolean("focusOnOneLabel", false);
        int focusLabel = config.getInteger("focusLabel", -1);


        /*
         *  Set some dummy actions, these won't actually be used unless config.getString("numberofActionRegisters") is "-1".
         */
        tpg.setActions(new long [] {0L, 1L, 2L, 3L, 4L, 5L});

        tpg.initialize();
        log.info("Constructing run metric!");
        ParametersMetric parametersMetric = ParametersMetric.of(tpg);
        JsonObject runData = new MetricBuilder().addComponent(runMetric).addComponent(datasetMetric).addComponent(parametersMetric).build();
        elasticsearchService.saveIntoIndex(List.of(runData),ES_INDEX_RUNS )
                .onSuccess(done->log.info("Run metric saved!"))
                .onFailure(err->log.error(err.getMessage(),err));



        log.info("Training...");

        log.info("remaining teams: {}", tpg.remainingTeams());



        for (int i = 0; i < config.getInteger("numGenerations"); i++){


            //Initialize data structures for tracking various metrics
            Map<String, Double> generationScoreSummary = new LinkedHashMap<>();


            //Collect runtime parameters into a metric to send to elasticsearch
            RuntimeParameters runtimeParameters = new RuntimeParameters();
            runtimeParameters.numRootTeams = tpg.rootTeams.size();
            runtimeParameters.generation = Optional.of((long)i);
            runtimeParameters.numLearners = tpg.learners.size();

            //Let every team classify
            while (tpg.remainingTeams() > 0){

                //Initialize data structure to track label-specific classification performance
                Map<Integer,LabelClassificationMetric> labelClassificationMap = new HashMap<>();

                //reset reward to 0 for each team that classifies
                double reward = 0.0;
                int correct = 0;
                //Go through the training exemplars
                Iterator<TrainingExemplar> it = trainingData.iterator();
                while (it.hasNext()){

                    TrainingExemplar exemplar = it.next();

                    double [] registerArray = tpg.participate(exemplar.featureVector());

                    double [] action = Arrays.copyOf(registerArray, Integer.parseInt(config.getString("numberofActionRegisters")) );

                    long [] predictedLabel = new long [1];
                    predictedLabel[0] = pathActions[(int)Math.floor(Math.abs(action[0]))%pathActions.length];

                    /** Tabulate the number of times a particular label is correctly classified
                     * We store this statistic in a {@link LabelClassificationMetric } object.
                     * The hashmap {@link labelClassificationMap } contains LabelClassificationMetrics for each label.
                     * So we fetch the appropriate object for the current training exemplar, and call its corresponding
                     * {@link LabelClassificationMetric#addCorrect()} or {@link LabelClassificationMetric#addIncorrect()} method.
                     *
                     * Then we update the hashmap.
                     * */
                    LabelClassificationMetric labelClassificationMetric = labelClassificationMap.getOrDefault(exemplar.labels()[0], new LabelClassificationMetric(exemplar.extras().getString("path"), exemplar.labels()[0]));

                    boolean isCorrect = false;
                    if(focusOnOneLabel){
                        isCorrect = isCorrect(predictedLabel[0], exemplar, focusLabel);
                    }else{
                        isCorrect = isCorrect(predictedLabel[0], exemplar);
                    }

                    if(isCorrect){
                        correct+=1;
                        labelClassificationMetric.addCorrect();
                    }else{
                        labelClassificationMetric.addIncorrect();
                    }
                    labelClassificationMetric.context = MetricContext.TRAINING;
                    labelClassificationMetric.teamId = Optional.of(tpg.getCurrTeamID());
                    labelClassificationMap.put(exemplar.labels()[0], labelClassificationMetric);

                    //NOTE: this reward is overwritten by classification % later.
                    reward += score(predictedLabel, exemplar);
                }

                //Compute reward to give to the current team
//                reward = ((double)correct/(double)trainingData.size())*100.0;

                if(focusOnOneLabel){
                    reward = ((double)correct/(double)datasetMetric.numberOfSamplesPerLabelInTrainingDataset)*100.0;
                }else{
                    reward = ((double)correct/(double)trainingData.size())*100.0;
                }

                generationScoreSummary.put(Long.toString(tpg.getCurrTeamID()), reward);
                tpg.reward(config.getString("trainingTaskName"), reward);

                //Send label classification metrics to elasticsearch.
                List<JsonObject> labelClassificationData = labelClassificationMap.entrySet()
                        .stream()
                        .map(entry->new MetricBuilder()
                                .addComponent(runMetric)
                                .addComponent(parametersMetric)
                                .addComponent(datasetMetric)
                                .addComponent(runtimeParameters)
                                .addComponent(entry.getValue())
                                .build()
                        ).collect(Collectors.toList());
                elasticsearchService.saveIntoIndex(labelClassificationData, ES_INDEX_LABEL_CLASSIFICATION)
                        .onSuccess(done->log.info("Saved label classification data for team {}", tpg.getCurrTeamID()))
                        .onFailure(err->log.error(err.getMessage(), err));

            }


            tpg.rootTeams.forEach(rootTeam->generationScoreSummary.put(Long.toString(rootTeam.ID), rootTeam.getOutcomeByKey(config.getString("trainingTaskName"))));

            List<Map.Entry<String,Double>> generationResults = new ArrayList(generationScoreSummary.entrySet());
            generationResults.sort(Map.Entry.comparingByValue());
            generationResults.forEach(entry->log.info("Team {}\t{}", entry.getKey(), entry.getValue()));




            //Compute fitness statistics
            Supplier<DoubleStream> generationScoresSupplier = ()->generationResults.stream().mapToDouble(entry->entry.getValue());

            double generationAverage = generationScoresSupplier.get().average().getAsDouble();
            double generationMin = generationScoresSupplier.get().min().getAsDouble();
            double generationMax = generationScoresSupplier.get().max().getAsDouble();

            //Collect fitness info into a metric to send to elasticsearch
            FitnessMetric fitnessMetric = new FitnessMetric();
            fitnessMetric.generation = Optional.of((long)i);
            fitnessMetric.minimum = Optional.of(generationMin);
            fitnessMetric.mean = Optional.of(generationAverage);
            fitnessMetric.maximum = Optional.of(generationMax);
            fitnessMetric.type = MetricContext.TRAINING;

            JsonObject genData = new MetricBuilder().addComponent(runMetric).addComponent(datasetMetric).addComponent(runtimeParameters).addComponent(parametersMetric).addComponent(fitnessMetric).build();
            elasticsearchService.saveIntoIndex(List.of(genData), ES_INDEX_TRAINING_FITNESS)
                    .onSuccess(done->log.info("Generation training results saved in elasticsearch!"))
                    .onFailure(err->log.error(err.getMessage(), err));


            log.info("Generation {} complete. Scores [Min: {}, Avg: {}, Max: {}]",tpg.getEpochs() , generationMin, generationAverage, generationMax);

            long mutationParameter =  config.getLong("mutationRoundsPerGeneration");

            //Perform selection
            tpg.selection();
            tpg.generateNewTeams(mutationParameter);
            tpg.nextEpoch();

        }

        //Testing
        List<Team> rootTeams = tpgAlgorithm.getTPGLearn().getRootTeams();

        //Initialize data structures for tracking various metrics
        Map<String,Double> championScores = new LinkedHashMap<>();


        Iterator<Team> it = rootTeams.iterator();

        while (it.hasNext()){
            //currTeam is the champion to test
            Team currTeam = it.next();

            //Initialize data structures for tracking label-specific classification performance
            Map<Integer, LabelClassificationMetric> labelClassificationMap = new HashMap<>();


            Iterator<TrainingExemplar> testDataIterator = testData.iterator();
            double reward = 0.0;
            int correct = 0;
            while (testDataIterator.hasNext()) {
                TrainingExemplar currExemplar = testDataIterator.next();
                double[] registerArray = currTeam.getAction(new HashSet<>(), currExemplar.featureVector());

                double[] action = Arrays.copyOf(registerArray, Integer.parseInt(config.getString("numberofActionRegisters")));

                long[] predictedLabel = new long[1];
                predictedLabel[0] = pathActions[(int) Math.floor(Math.abs(action[0])) % pathActions.length];

                /** Tabulate the number of times a particular label is correctly classified
                 * We store this statistic in a {@link LabelClassificationMetric } object.
                 * The hashmap {@link labelClassificationMap } contains LabelClassificationMetrics for each label.
                 * So we fetch the appropriate object for the current training exemplar, and call its corresponding
                 * {@link LabelClassificationMetric#addCorrect()} or {@link LabelClassificationMetric#addIncorrect()} method.
                 *
                 * Then we update the hashmap.
                 * */
                LabelClassificationMetric labelClassificationMetric = labelClassificationMap.getOrDefault(currExemplar.labels()[0], new LabelClassificationMetric(currExemplar.extras().getString("path"), currExemplar.labels()[0]));
                boolean isCorrect = false;

                if(focusOnOneLabel){
                    isCorrect = isCorrect(predictedLabel[0], currExemplar, focusLabel);
                }else{
                    isCorrect = isCorrect(predictedLabel[0], currExemplar);
                }

                if (isCorrect) {
                    correct += 1;
                    labelClassificationMetric.addCorrect();
                } else {
                    labelClassificationMetric.addIncorrect();
                }
                labelClassificationMetric.context = MetricContext.TEST;
                labelClassificationMetric.teamId = Optional.of(currTeam.ID);
                labelClassificationMap.put(currExemplar.labels()[0], labelClassificationMetric);

                //NOTE: gets overriden by % classification later.
                reward += score(predictedLabel, currExemplar);

            }

            //reward = ((double)correct/(double)testData.size())*100.0;

            if(focusOnOneLabel){
                reward = ((double)correct/(double)datasetMetric.numberOfSamplesPerLabelInTestDataset)*100.0;
            }else{
                reward = ((double)correct/(double)testData.size())*100.0;
            }

            championScores.put(Long.toString(currTeam.ID), reward);

            /**
             * Send label classification results to elasticsearch
             */
            List<JsonObject> labelClassificationData = labelClassificationMap.entrySet()
                    .stream()
                    .map(entry->new MetricBuilder()
                            .addComponent(runMetric)
                            .addComponent(parametersMetric)
                            .addComponent(datasetMetric)
                            .addComponent(entry.getValue())
                            .build()
                    ).collect(Collectors.toList());
            elasticsearchService.saveIntoIndex(labelClassificationData, ES_INDEX_LABEL_CLASSIFICATION)
                    .onSuccess(done->log.info("Saved label classification metrics during testing to elasticsearch!"))
                    .onFailure(err->log.error(err.getMessage(), err));
        }

        //Sort test results
        List<Map.Entry<String,Double>> testResults = new ArrayList<>(championScores.entrySet());
        testResults.sort(Map.Entry.comparingByValue());



        /**
         * Send champion test results to elastic search
         */
        List<JsonObject> championResults = new ArrayList<>();
        testResults.forEach(entry->{
            log.info("Champion {} \t{}", entry.getKey(), entry.getValue());

            FitnessMetric testResultMetric = new FitnessMetric();
            testResultMetric.type = MetricContext.TEST;
            testResultMetric.teamId = Optional.of(Long.parseLong(entry.getKey()));
            testResultMetric.score = Optional.of(entry.getValue());
            testResultMetric.generation = Optional.of(config.getLong("numGenerations"));

            JsonObject testData = new MetricBuilder().addComponent(runMetric).addComponent(datasetMetric).addComponent(testResultMetric).build();
            championResults.add(testData);

        });

        elasticsearchService.saveIntoIndex(championResults, ES_INDEX_CHAMPION_TEST_FITNESS )
                .onSuccess(done->log.info("Successfully saved champion test results!"))
                .onFailure(err->log.error(err.getMessage(), err));


        //Compute some stats for the whole champion population
        Supplier<DoubleStream> testScoresSupplier = ()->testResults.stream().mapToDouble(entry->entry.getValue());

        double championAverage = testScoresSupplier.get().average().getAsDouble();
        double championMin = testScoresSupplier.get().min().getAsDouble();
        double championMax = testScoresSupplier.get().max().getAsDouble();

        log.info("Champion Test Scores [Min: {}, Avg: {}, Max: {}]", championMin, championAverage, championMax);


        taskPromise.complete(tpgAlgorithm);

        }catch (Exception e){
            log.error(e.getMessage(), e);
        }
    }

    private boolean isCorrect(long predictedLabel, TrainingExemplar exemplar, int focusLabel){
        long [] correctLabel = Arrays.stream(exemplar.labels()).mapToLong(i->(long)i).toArray();



        if (predictedLabel == correctLabel[0] && correctLabel[0] == (long)focusLabel){
            return true;
        }



        return false;
    }

    private boolean isCorrect(long predictedLabel, TrainingExemplar exemplar){
        long [] correctLabel = Arrays.stream(exemplar.labels()).mapToLong(i->(long)i).toArray();

        return predictedLabel == correctLabel[0];
    }

    private double score(long [] predictedLabel, TrainingExemplar exemplar){
        long [] correctLabel = Arrays.stream(exemplar.labels()).mapToLong(i->(long)i).toArray();

        double score = 0.0;


        if(predictedLabel[0] == correctLabel[0]){
            score += 1.0;
        }else{
            score -= 1.0;
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
                .mapToLong(exemplar->exemplar.labels()[dimension])
                .distinct()
                .toArray();

    }

}
