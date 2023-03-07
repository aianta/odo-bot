package ca.ualberta.odobot.semanticflow.similarity;

import ca.ualberta.odobot.semanticflow.model.TimelineEntity;

public interface ISimilarityStrategy {


    double computeSimilarity(TimelineEntity entity1, TimelineEntity entity2);
}
