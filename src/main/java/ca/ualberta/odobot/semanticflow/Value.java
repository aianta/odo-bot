package ca.ualberta.odobot.semanticflow;

import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Value {
    private static final Logger log = LoggerFactory.getLogger(Value.class);

    private String outerHTML;
    private String tag;
    private JsonObject styles;
    private JsonObject attributes;

}
