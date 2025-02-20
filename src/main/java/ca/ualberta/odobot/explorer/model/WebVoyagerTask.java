package ca.ualberta.odobot.explorer.model;

import io.vertx.core.json.JsonObject;

//TODO: consider using this class instead of messing with JsonObjects directly in EvaluationTaskGenerationTask
public class WebVoyagerTask extends JsonObject {

    public String web(){
        return getString("web");
    }

    public WebVoyagerTask web(String web){
        put("web", web);
        return this;
    }

    public String webName(){
        return getString("web_name");
    }

    public WebVoyagerTask webName(String webName){
        put("webName", webName);
        return this;
    }

    public String description(){
        return getString("description");
    }

    public WebVoyagerTask description(String description){
        put("description", description);
        return this;
    }

    public String id(){
        return getString("id");
    }

    public WebVoyagerTask id(String id){
        put("id", id);
        return this;
    }

    public String question(){
        return getString("question");
    }

    public WebVoyagerTask question(String ques){
        put("ques", ques);
        return this;
    }

}
