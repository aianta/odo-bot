package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonObject;

public class TinymceEvent extends InputChange implements TimelineEntity {

    String editorId;
    String inputType;

    @Override
    public int size() {
        return 0;
    }

    @Override
    public String symbol() {
        return "TNY_MCE_DE";
    }

    public TinymceEvent setInputType(String inputType) {
        this.inputType = inputType;
        return this;
    }

    public String getInputType() {
        return inputType;
    }

    public TinymceEvent setEditorId(String editorId) {
        this.editorId = editorId;
        return this;
    }

    public String getEditorId(){
        return editorId;
    }

    @Override
    public JsonObject toJson() {
        JsonObject result = new JsonObject()
                .put("xpath", getXpath())
                .put("editorId", getEditorId());

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
