package ca.ualberta.odobot.semanticflow.exceptions;

import ca.ualberta.odobot.semanticflow.SemanticSequencer;
import io.vertx.core.json.JsonObject;

public class InvalidTimestamp extends RecordException{
    String value;
    public InvalidTimestamp(JsonObject record) {
        super(record);
        this.value = record.getString(SemanticSequencer.TIMESTAMP_FIELD);
    }

    public String getMessage(){
        return "Timestamp: "+value+" could not be parsed in record! \n" + record.encodePrettily();
    }
}
