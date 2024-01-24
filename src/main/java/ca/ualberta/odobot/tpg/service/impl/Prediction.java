package ca.ualberta.odobot.tpg.service.impl;

import ca.ualberta.odobot.sqlite.impl.TrainingExemplar;

public record Prediction(long predictedLabel, TrainingExemplar exemplar, long teamId){};
