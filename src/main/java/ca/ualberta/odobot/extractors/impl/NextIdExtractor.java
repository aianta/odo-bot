package ca.ualberta.odobot.extractors.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;

public class NextIdExtractor implements SemanticArtifactExtractor<TimelineEntity> {
    @Override
    public String artifactName() {
        return "nextId";
    }

    @Override
    public Object extract(TimelineEntity entity, int index, Timeline timeline) {

        if(index + 1 < timeline.size()){
            TimelineEntity nextEntity = timeline.get(index + 1);
            return timeline.getId().toString() + "#" + (index + 1);
        }

        return "";
    }
}
