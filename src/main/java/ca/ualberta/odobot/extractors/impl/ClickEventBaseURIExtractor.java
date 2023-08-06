package ca.ualberta.odobot.extractors.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import ca.ualberta.odobot.semanticflow.model.Timeline;

public class ClickEventBaseURIExtractor implements SemanticArtifactExtractor<ClickEvent> {
    @Override
    public String artifactName() {
        return "baseURI";
    }

    @Override
    public Object extract(ClickEvent entity, int index, Timeline timeline) {
        return entity.getBaseURI();
    }
}
