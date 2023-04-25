package ca.ualberta.odobot.semanticflow.exceptions;

import ca.ualberta.odobot.semanticflow.SemanticSequencer;
import io.vertx.core.json.JsonObject;

public class MissingSessionId extends RecordException{


    public MissingSessionId(JsonObject record) {
        super(record);
    }

    public String getMessage(){
        return "Record is missing session Id field ("+ SemanticSequencer.SESSION_ID_FIELD +")! \n" + record.encodePrettily();
    }
}
