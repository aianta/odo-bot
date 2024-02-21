package ca.ualberta.odobot.explorer.canvas.resources;

import io.vertx.core.json.JsonObject;

public class Assignment extends BaseResource{

    private String name;

    private String body;

    private int id = -1;

    private String assignmentPageUrl;

    public String getAssignmentPageUrl() {
        return assignmentPageUrl;
    }

    /**
     * Returns the runtime id of the assignment, that is, the id of the course as it appears in canvas after it is generated
     * during the data generation process. This is different from the identifiers that are loaded from the IMSCC file.
     * @return the runtime id of the assignment
     */
    public int getId(){
        return this.id;
    }

    public void setAssignmentPageUrl(String assignmentPageUrl) {

        this.assignmentPageUrl = assignmentPageUrl;
        if(id == -1){
            this.id = parseIdFromUrl(this.assignmentPageUrl, "assignments");
        }
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
