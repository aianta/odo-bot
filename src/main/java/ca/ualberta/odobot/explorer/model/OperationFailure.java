package ca.ualberta.odobot.explorer.model;

import ca.ualberta.odobot.explorer.canvas.resources.Page;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OperationFailure {

    private static final Logger log = LoggerFactory.getLogger(OperationFailure.class);

    Operation failedOperation;
    Throwable exception;

    String url; //The url of the driver at the time of the exception.


    public OperationFailure(Operation operation, Throwable e, String url){
        this.exception = e;
        this.failedOperation = operation;
        this.url = url;
    }

    public JsonObject toJson(){
        JsonObject result = failedOperation.toJson()
                .put("error", new JsonObject()
                        .put("urlAtException", url)
                        .put("message", exception.getMessage()));

        return result;

    }

}
