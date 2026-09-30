package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonObject;

/**
 * A snapshot of the page sent by OdoX. The first one arrives right after START_TRANSMISSION and is the first
 * observation an agent receives. OdoX also sends one after an uncharted action that produces no other event
 * carrying a screenshot.
 */
public class Observation implements TimelineEntity{

    /**
     * What made OdoX send the observation.
     */
    public enum Trigger {
        START_TRANSMISSION,
        UNCHARTED_ACTION
    }

    private final Trigger trigger;
    private final JsonObject triggerDetails;
    private final String userLocation;
    private final Screenshot screenshot;
    private final long timestamp;

    /**
     * @param triggerDetails the uncharted action that triggered the observation, or null.
     * @param userLocation the url in the browser's address bar, or null.
     * @param screenshot the screenshot of the page, or null.
     */
    public Observation(Trigger trigger, JsonObject triggerDetails, String userLocation, Screenshot screenshot, long timestamp){
        this.trigger = trigger;
        this.triggerDetails = triggerDetails;
        this.userLocation = userLocation;
        this.screenshot = screenshot;
        this.timestamp = timestamp;
    }

    public Trigger getTrigger(){
        return trigger;
    }

    /**
     * @return the JSON of the uncharted action that triggered the observation when the trigger is
     * {@link Trigger#UNCHARTED_ACTION}, otherwise null.
     */
    public JsonObject getTriggerDetails(){
        return triggerDetails;
    }

    /**
     * @return the url of the page the user was on when the observation was made, as shown in the browser's address
     * bar, or null if OdoX did not send one.
     */
    public String getUserLocation(){
        return userLocation;
    }

    /**
     * @return the screenshot of the page when the observation was made, or null if OdoX did not send one.
     */
    @Override
    public Screenshot getScreenshot(){
        return screenshot;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public String symbol() {
        return "OBS";
    }

    @Override
    public JsonObject toJson() {
        return new JsonObject()
                .put("trigger", trigger.name())
                .put("triggerDetails", triggerDetails)
                .put("userLocation", userLocation);
    }

    @Override
    public long timestamp() {
        return timestamp;
    }

    @Override
    public JsonObject getSemanticArtifacts() {
        return new JsonObject();
    }
}
