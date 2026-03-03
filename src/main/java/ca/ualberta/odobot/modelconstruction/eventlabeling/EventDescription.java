package ca.ualberta.odobot.modelconstruction.eventlabeling;

import ca.ualberta.odobot.semanticflow.model.TimelineEntity;

public class EventDescription {
    private String description;
    private TimelineEntity entity;

    public EventDescription(String description, TimelineEntity entity) {
        this.description = description;
        this.entity = entity;
    }

    public String getDescription() {
        return description;
    }

    public EventDescription setDescription(String description) {
        this.description = description;
        return this;
    }

    public TimelineEntity getEntity() {
        return entity;
    }

    public EventDescription setEntity(TimelineEntity entity) {
        this.entity = entity;
        return this;
    }
}
