package ca.ualberta.odobot.guidance.instructions;

import io.vertx.core.json.JsonObject;

public class DoClick extends XPathInstruction{
    public String toString(){
        return "DoClick on XPath " + xpath;
    }

    public JsonObject toJson(){
        return super.toJson()
                .put("action", "click")
                .put("xpath", xpath);
    }
}
