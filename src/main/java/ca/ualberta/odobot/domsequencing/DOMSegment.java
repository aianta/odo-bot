package ca.ualberta.odobot.domsequencing;

import io.vertx.core.json.JsonObject;

import java.util.Set;

public record DOMSegment (String tag, String className) {

    JsonObject toJson(){
        var result = new JsonObject()
                .put("tag", tag)
                .put("class", className);
        return result;
    }

}
