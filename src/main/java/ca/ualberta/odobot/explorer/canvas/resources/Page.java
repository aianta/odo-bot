package ca.ualberta.odobot.explorer.canvas.resources;

import io.vertx.core.json.JsonObject;

public class Page extends BaseResource{

    private String title;

    private String pageUrl;

    private String body;


    public String getPageUrl() {
        return pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    @Override
    public JsonObject getRuntimeData() {
        JsonObject result = new JsonObject();
        if(getPageUrl() != null){
            result.put("pageUrl", getPageUrl());
        }
        return result;
    }

    @Override
    public void setRuntimeData(JsonObject data) {
        if(data.containsKey("pageUrl")){
            setPageUrl(data.getString("pageUrl"));
        }
    }

    public JsonObject toJson(){
        JsonObject result = super.toJson()
                .put("title", getTitle())
                .put("body", getBody());

        return result;
    }
}
