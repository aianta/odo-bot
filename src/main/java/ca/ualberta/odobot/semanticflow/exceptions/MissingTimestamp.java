package ca.ualberta.odobot.semanticflow.exceptions;

import ca.ualberta.odobot.semanticflow.SemanticSequencer;
import io.vertx.core.json.JsonObject;

public class MissingTimestamp extends RecordException{
    public MissingTimestamp(JsonObject record) {
        super(record);
    }

    public String getMessage(){
        return "Record is missing timestamp field ("+ SemanticSequencer.TIMESTAMP_FIELD +")! \n" + record.encodePrettily();
    }
}
