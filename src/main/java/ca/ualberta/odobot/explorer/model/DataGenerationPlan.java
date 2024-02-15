package ca.ualberta.odobot.explorer.model;

import ca.ualberta.odobot.explorer.PlanRequestFields;
import ca.ualberta.odobot.explorer.canvas.resources.CourseResources;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DataGenerationPlan {

    private UUID id = UUID.randomUUID();

    private List<String> coursePaths = new ArrayList<>();

    private List<CourseResources> resourceList = new ArrayList<>();

    private ToDo caseManifest = new ToDo();


    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public List<String> getCoursePaths() {
        return coursePaths;
    }

    public void setCoursePaths(List<String> coursePaths) {
        this.coursePaths = coursePaths;
    }

    public List<CourseResources> getResourceList() {
        return resourceList;
    }

    public void setResourceList(List<CourseResources> resourceList) {
        this.resourceList = resourceList;
    }

    public ToDo getCaseManifest() {
        return caseManifest;
    }

    public void setCaseManifest(ToDo caseManifest) {
        this.caseManifest = caseManifest;
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject();
        result.put("id", id.toString())
                .put("manifestSize", caseManifest.size())
                .put(PlanRequestFields.COURSES.field(), getCoursePaths().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("manifest", caseManifest.stream().map(Operation::toJson).collect(JsonArray::new, JsonArray::add, JsonArray::addAll));

        return result;
    }
}
