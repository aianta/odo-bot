package ca.ualberta.odobot.explorer.canvas.resources;

import io.vertx.core.json.JsonObject;

import java.net.MalformedURLException;
import java.net.URL;

public class Course extends BaseResource{

    String name;

    String coursePageUrl;

    int id = -1;

    public String getName() {
        return name;
    }

    /**
     * Returns the runtime id of the course, that is, the id of the course as it appears in canvas after it is generated
     * during the data generation process. This is different from the identifiers that are loaded from the IMSCC file.
     * @return the runtime id of the course
     */
    public int getId(){
        return id;
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
        if(this.id == -1){
            this.id = parseIdFromUrl(this.coursePageUrl, "courses");
        }
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

    public JsonObject toJson(){
        JsonObject result = super.toJson()
                .put("name",getName());

        return result;
    }
}
