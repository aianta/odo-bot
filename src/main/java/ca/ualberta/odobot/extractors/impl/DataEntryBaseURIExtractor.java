package ca.ualberta.odobot.extractors.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.semanticflow.model.DataEntry;
import ca.ualberta.odobot.semanticflow.model.Timeline;

public class DataEntryBaseURIExtractor implements SemanticArtifactExtractor<DataEntry>{
    @Override
    public String artifactName() {
        return "baseURI";
    }

    @Override
    public Object extract(DataEntry entity, int index, Timeline timeline) {
        return entity.lastChange().getBaseURI();
    }
}
