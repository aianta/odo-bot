package ca.ualberta.odobot.explorer.canvas.resources;

import io.vertx.core.json.JsonObject;

import java.net.MalformedURLException;
import java.net.URL;

public class Course extends BaseResource{

    String name;

    String coursePageUrl;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCoursePageUrl() {
        return coursePageUrl;
    }

    public URL getCoursePageUrlAsURL(){
        try {
            return new URL(coursePageUrl);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    public void setCoursePageUrl(String coursePageUrl) {
        this.coursePageUrl = coursePageUrl;
    }

    @Override
    public JsonObject getRuntimeData() {
        JsonObject result = new JsonObject();
        if(getCoursePageUrl() != null){
            result.put("coursePageUrl", getCoursePageUrl());
        }
        return result;
    }

    @Override
    public void setRuntimeData(JsonObject data) {

        if(data.containsKey("coursePageUrl")){
            setCoursePageUrl(data.getString("coursePageUrl"));
        }

    }
}
