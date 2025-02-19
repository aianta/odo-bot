package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonObject;

public class CheckboxEvent extends InputChange implements TimelineEntity{

    @Override
    public int size() {
        return 1;
    }

    @Override
    public String symbol() {
        return "CHKBX";
    }

    public String xpath(){
        return getXpath();
    }

    @Override
    public JsonObject toJson() {
        JsonObject result = new JsonObject()
                .put("xpath", getXpath())
                .put("element", getOuterHTML());

        if(getBaseURI() != null){
            result.put("baseURI", getBaseURI());
        }

        return result;
    }

    @Override
    public long timestamp() {
        return getTimestamp().toInstant().toEpochMilli();
    }

}
