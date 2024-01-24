package ca.ualberta.odobot.tpg.service;

import ca.ualberta.odobot.tpg.service.impl.Prediction;

public interface FitnessStrategy {

    void onPrediction(Prediction prediction);

    double getReward();

}
