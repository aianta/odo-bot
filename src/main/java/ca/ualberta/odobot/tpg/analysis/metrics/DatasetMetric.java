package ca.ualberta.odobot.tpg.analysis.metrics;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;

@DataObject
public class DatasetMetric implements MetricComponent {

    private static final String JSON_PREFIX = "dataset_";
    public int numberOfInputTrainingExemplars;

    public int balancedTotalDatasetSize;

    public int trainingDatasetSize;

    public int testDatasetSize;

    public Integer numberOfSamplesPerLabelInBalancedDataset;

    public Integer numberOfSamplesPerLabelInTestDataset;

    public Integer numberOfSamplesPerLabelInTrainingDataset;


    public DatasetMetric(){}

    public DatasetMetric(JsonObject data){
        this.numberOfInputTrainingExemplars = data.getInteger(JSON_PREFIX+"numberOfInputTrainingExemplars");
        this.balancedTotalDatasetSize = data.getInteger(JSON_PREFIX+"balancedTotalDatasetSize");
        this.trainingDatasetSize = data.getInteger(JSON_PREFIX+"trainingDatasetSize");
        this.testDatasetSize = data.getInteger(JSON_PREFIX+"testDatasetSize");

        if(data.containsKey(JSON_PREFIX+"numberOfSamplesPerLabelInBalancedDataset")){
            this.numberOfSamplesPerLabelInBalancedDataset = data.getInteger(JSON_PREFIX+"numberOfSamplesPerLabelInBalancedDataset");
        }

        if(data.containsKey(JSON_PREFIX+"numberOfSamplesPerLabelInTestDataset")){
            this.numberOfSamplesPerLabelInTestDataset = data.getInteger(JSON_PREFIX+"numberOfSamplesPerLabelInTestDataset");
        }

        if(data.containsKey(JSON_PREFIX+"numberOfSamplesPerLabelInTrainingDataset")){
            this.numberOfSamplesPerLabelInTrainingDataset = data.getInteger(JSON_PREFIX+"numberOfSamplesPerLabelInTrainingDataset");
        }


    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject()
                .put(JSON_PREFIX+"numberOfInputTrainingExemplars", numberOfInputTrainingExemplars)
                .put(JSON_PREFIX+"balancedTotalDatasetSize", balancedTotalDatasetSize)
                .put(JSON_PREFIX+"trainingDatasetSize", trainingDatasetSize)
                .put(JSON_PREFIX+"testDatasetSize", testDatasetSize);


        if(numberOfSamplesPerLabelInBalancedDataset != null){
            result.put(JSON_PREFIX+"numberOFSamplesPerLabelInBalancedDataset",numberOfSamplesPerLabelInBalancedDataset);
        }

        if(numberOfSamplesPerLabelInTestDataset != null){
            result.put(JSON_PREFIX+"numberOfSamplesPerLabelInTestDataset", numberOfSamplesPerLabelInTestDataset);
        }

        if(numberOfSamplesPerLabelInTrainingDataset != null){
            result.put(JSON_PREFIX+"numberofSamplesPerLabelInTrainingDataset", numberOfSamplesPerLabelInTrainingDataset);
        }


        return result;
    }

}
