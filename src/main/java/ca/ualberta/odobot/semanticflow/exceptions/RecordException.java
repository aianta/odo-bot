package ca.ualberta.odobot.semanticflow.exceptions;

import io.vertx.core.json.JsonObject;

public class RecordException extends Exception{

    JsonObject record;

    public RecordException(JsonObject record){
        this.record = record;
    }

    public JsonObject getRecord(){
        return record;
    }
}
