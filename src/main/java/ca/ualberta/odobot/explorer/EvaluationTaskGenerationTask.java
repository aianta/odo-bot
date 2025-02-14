package ca.ualberta.odobot.explorer;

import ca.ualberta.odobot.explorer.canvas.resources.*;
import ca.ualberta.odobot.explorer.canvas.resources.Module;
import ca.ualberta.odobot.explorer.model.DataGenerationPlan;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A class implementing a threaded task that produces tasks definitions in the appropriate format
 * for the WebVoyager agent.
 */
public class EvaluationTaskGenerationTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(EvaluationTaskGenerationTask.class);

    JsonObject config;

    Promise<JsonArray> promise;

    List<Course> courses;

    private Random random = new Random();

    private final String appUrl;
    private final String appName;
    private final String username;
    private final String password;


    public EvaluationTaskGenerationTask(JsonObject config, Promise<JsonArray> promise){
        this.config = config;
        this.promise = promise;
        this.appUrl = config.getString(EvaluationTaskGenerationRequestFields.TARGET_APP_URL.field());
        this.appName = config.getString(EvaluationTaskGenerationRequestFields.TARGET_APP_NAME.field());
        this.username = config.getString(EvaluationTaskGenerationRequestFields.TARGET_APP_USERNAME.field());
        this.password = config.getString(EvaluationTaskGenerationRequestFields.TARGET_APP_PASSWORD.field());
    }

    @Override
    public void run() {

        DataGenerationPlan plan = new DataGenerationPlan();
        //Init course list
        plan.setCoursePaths(
                config.getJsonArray(EvaluationTaskGenerationRequestFields.COURSES.field()).stream().map(o->(String)o).collect(Collectors.toList())
        );

        //Load course resources from files.
        plan.setResourceList(
                config.getJsonArray(EvaluationTaskGenerationRequestFields.COURSES.field()).stream()
                        .map(o->(String)o)
                        .map(ResourceManager::loadCourse)
                        .collect(Collectors.toList())
        );

        //Generate WebVoyager inputs using the config and the course data.
        List<JsonObject> tasks = config.getJsonArray("tasks").stream().map(o->(JsonObject)o).collect(Collectors.toList());

        JsonArray results = tasks.stream().map(task->makeTaskInstance(task, config.getJsonArray(EvaluationTaskGenerationRequestFields.LOGIN_TEMPLATES.field()), plan.getResourceList()))
                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

        promise.complete(results);
    }

    /**
     *
     * @param taskDetails A json object with 'task', 'id', 'parameters', and 'template' fields.
     * @param loginTemplates
     * @param courseResources Course resources to fill in the templates.
     * @return a JSON object containing a WebVoyager formatted task and an Odobot formatted task under the fields 'webVoyager' and 'odoBot' respectively.
     */
    private JsonObject makeTaskInstance(JsonObject taskDetails, JsonArray loginTemplates, List<CourseResources> courseResources){

        JsonObject result = new JsonObject();

        //We don't want to repeat/reuse quiz, module, assignment, and page names, so we have to create a structure that keeps track of the ones we haven't used yet.
        Map<String,Map<String,List<String>>> parameterValues = new HashMap<>();
        List<String> courses = new ArrayList<>();
        for(CourseResources resources: courseResources){

            HashMap<String, List<String>> resourceLists = new HashMap<>();
            resourceLists.put("assignments", resources.assignments().stream().map(Assignment::getName).collect(Collectors.toList()));
            resourceLists.put("pages", resources.pages().stream().map(Page::getTitle).collect(Collectors.toList()));
            resourceLists.put("quizzes", resources.quizzes().stream().map(Quiz::getName).collect(Collectors.toList()));
            resourceLists.put("modules", resources.modules().stream().map(Module::getName).collect(Collectors.toList()));

            parameterValues.put(resources.getCourse().getName(), resourceLists);
            courses.add(resources.getCourse().getName());
        }

        result.put("webVoyager", makeWebVoyagerTask(taskDetails, loginTemplates, courses, parameterValues));

        return result;
    }

    private JsonObject makeWebVoyagerTask(JsonObject taskDetails, JsonArray loginTemplates,List<String> courses, Map<String,Map<String,List<String>>> parameterValues){

        JsonObject result = new JsonObject();
        result.put("web", this.appUrl);
        result.put("web_name", this.appName);
        result.put("description", taskDetails.getString("task"));

        String webVoyagerTaskId = taskDetails.getString("id") + "|WebVoyager|" + UUID.randomUUID().toString();
        result.put("id", webVoyagerTaskId);

        StringBuilder sb = new StringBuilder();
        //Inject the login instruction.
        sb.append(loginTemplates.getString(random.nextInt(loginTemplates.size())).replace("<username>", this.username).replace("<password>", this.password));

        //Resolve template parameters
        //Select a course from which to populate the parameters
        String chosenCourse = courses.get(random.nextInt(courses.size()));
        //Retrieve the resource values for that course.
        Map<String,List<String>> courseValues = parameterValues.get(chosenCourse);

        //Identify the parameters we will need values for
        JsonArray taskParameters = taskDetails.getJsonArray("parameters");
        //Get values for those parameters from the available course values
        JsonObject values = getRandomParameterValues(taskParameters, chosenCourse, courseValues);

        //Fill the template with the chosen values.
        String chosenTemplate = taskDetails.getJsonArray("templates").getString(random.nextInt(taskDetails.getJsonArray("templates").size()));

        List<Map.Entry<String,Object>> _values = values.stream().collect(Collectors.toList());
        for(Map.Entry<String,Object> entry: _values){
            chosenTemplate = chosenTemplate.replace("<"+entry.getKey()+">", (String)entry.getValue());
        };

        //Inject the task instruction
        sb.append(chosenTemplate);
        result.put("ques", sb.toString());

        return result;

    }

    private JsonObject getRandomParameterValues(JsonArray parameters, String course, Map<String,List<String>> parameterValues){

        JsonObject result = new JsonObject();

        parameters.stream().map(p->(String)p).forEach(param->{
            result.put(param, getRandomParameterValue(param, course, parameterValues));
        });

        return result;

    }

    private String lastOldQuizTitle;
    private String lastOldPageTitle;
    private String lastOldAssignmentTitle;
    private String lastOldModuleName;

    private String getRandomParameterValue(String parameter, String course, Map<String,List<String>> parameterValues){
        return switch (parameter){
            case "course" -> {
                yield course;
            }
            case "assignment-title" -> parameterValues.get("assignments").remove(0);
            case "page-title" -> parameterValues.get("pages").remove(0);
            case "module-name" -> parameterValues.get("modules").remove(0);
            case "old-quiz-title" -> {
                String candidate = parameterValues.get("quizzes").remove(0);
                lastOldQuizTitle = candidate;
                yield candidate;
            }
            case "new-quiz-title" -> {
                yield "modified - " + lastOldQuizTitle;
            }
            case "old-page-title" -> {
                String candidate = parameterValues.get("pages").remove(0);
                lastOldPageTitle = candidate;
                yield candidate;
            }
            case "new-page-title" ->{
                yield "modified - " + lastOldPageTitle;
            }
            case "old-module-name" ->{
                String candidate = parameterValues.get("modules").remove(0);
                lastOldModuleName = candidate;
                yield candidate;
            }
            case "new-module-name" ->{
                yield "modified - " + lastOldModuleName;
            }
            case "old-assignment-title" -> {
                String candidate = parameterValues.get("assignments").remove(0);
                lastOldAssignmentTitle = candidate;
                yield candidate;
            }
            case "new-assignment-title" ->{
                yield "modified - " + lastOldAssignmentTitle;
            }
            case "assignment" -> parameterValues.get("assignments").remove(0);
            case "page" -> parameterValues.get("pages").remove(0);
            default -> "UNKNOWN PARAMETER";
        };
    }
}
