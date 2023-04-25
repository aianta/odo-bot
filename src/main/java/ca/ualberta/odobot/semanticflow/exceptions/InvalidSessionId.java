package ca.ualberta.odobot.semanticflow.exceptions;

import ca.ualberta.odobot.semanticflow.SemanticSequencer;
import io.vertx.core.json.JsonObject;

public class InvalidSessionId extends RecordException{

    String value;

    public InvalidSessionId(JsonObject record) {
        super(record);
        this.value = record.getString(SemanticSequencer.SESSION_ID_FIELD);
    }

    public String getMessage(){
        return "Session ID value: " + value + " is not a valid sessionID! \n" + record.encodePrettily();
    }
}
