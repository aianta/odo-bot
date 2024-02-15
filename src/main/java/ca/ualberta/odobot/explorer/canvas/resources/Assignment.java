package ca.ualberta.odobot.explorer.canvas.resources;

import io.vertx.core.json.JsonObject;

public class Assignment extends BaseResource{

    private String name;

    private String body;

    private String assignmentPageUrl;

    public String getAssignmentPageUrl() {
        return assignmentPageUrl;
    }

    public void setAssignmentPageUrl(String assignmentPageUrl) {
        this.assignmentPageUrl = assignmentPageUrl;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    @Override
    public JsonObject getRuntimeData() {
        JsonObject result = new JsonObject();

        if(getAssignmentPageUrl() != null){
            result.put("assignmentPageUrl", getAssignmentPageUrl());
        }
        return result;
    }

    @Override
    public void setRuntimeData(JsonObject data) {
        if(data.containsKey("assignmentPageUrl")){
            setAssignmentPageUrl(data.getString("assignmentPageUrl"));
        }
    }
}
