package ca.ualberta.odobot.modelconstruction.eventlabeling;

import ca.ualberta.odobot.semanticflow.model.TimelineEntity;

import java.time.Instant;

public class EventDescription {
    private String description;
    private TimelineEntity entity;
    private String model;
    private int eventIndex;
    private final Instant timestamp;

    public EventDescription(String description, TimelineEntity entity, String model, int eventIndex) {
        this.description = description;
        this.entity = entity;
        this.eventIndex = eventIndex;
        this.model = model;
        this.timestamp = Instant.now();
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

    public String timestamp(){
        return timestamp.toString();
    }

    public String model() {
        return model;
    }

    public int eventIndex() {
        return eventIndex;
    }
}
