package ca.ualberta.odobot.tpg.service.impl;

import ca.ualberta.odobot.tpg.service.FitnessStrategy;

public class ClassificationPercent implements FitnessStrategy {

    int correct = 0;
    int total = 0;

    public ClassificationPercent(int total){
        this.total = total;
    }

    @Override
    public void onPrediction(Prediction prediction) {
        if(prediction.predictedLabel() == prediction.exemplar().getLabel()){
            correct+=1;
        }
    }

    @Override
    public double getReward() {
        return ((double)correct/(double) total)*100.0;
    }
}
