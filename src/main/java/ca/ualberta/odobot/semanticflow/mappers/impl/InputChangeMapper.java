package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.InputChange;
import io.vertx.core.json.JsonObject;

/**
 * {@link InputChangeMapper} contains all the logic necessary to produce a {@link ca.ualberta.odobot.semanticflow.model.InputChange} object
 * from a json object with the correct information.
 */
public class InputChangeMapper extends JsonMapper<InputChange> {

    @Override
    public InputChange map(JsonObject event) {
        return null;
    }
}
