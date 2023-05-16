package ca.ualberta.odobot.logpreprocessor.executions;

import io.vertx.core.json.JsonObject;

/**
 * Describes where an external artifact is stored.
 */
public record ExternalArtifact(Location location, String path) {

    public enum Location{
        LOCAL_FILE_SYSTEM
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject()
                .put("location", location.name())
                .put("path", path());
        return result;
    }

    public static ExternalArtifact fromJson(JsonObject input){
        Location location = Location.valueOf(input.getString("location"));
        String path = input.getString("path");
        return new ExternalArtifact(location, path);
    }

}
