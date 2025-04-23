package ca.ualberta.odobot.explorer;

import ca.ualberta.odobot.explorer.canvas.resources.*;
import ca.ualberta.odobot.explorer.canvas.resources.Module;
import ca.ualberta.odobot.explorer.model.DataGenerationPlan;
import ca.ualberta.odobot.explorer.model.OdoBotExecutionRequest;
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

//        JsonArray results = tasks.stream().map(task->makeTaskInstance(task, config.getJsonArray(EvaluationTaskGenerationRequestFields.LOGIN_TEMPLATES.field()), plan.getResourceList()))
//                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

        //We don't want to repeat/reuse quiz, module, assignment, and page names, so we have to create a structure that keeps track of the ones we haven't used yet.
        Map<String,Map<String,List<String>>> parameterValues = new HashMap<>();
        List<String> courses = new ArrayList<>();
        for(CourseResources resources: plan.getResourceList()){

            HashMap<String, List<String>> resourceLists = new HashMap<>();
            resourceLists.put("assignments", resources.assignments().stream().map(Assignment::getName).collect(Collectors.toList()));
            resourceLists.put("pages", resources.pages().stream().map(Page::getTitle).collect(Collectors.toList()));
            resourceLists.put("quizzes", resources.quizzes().stream().map(Quiz::getName).collect(Collectors.toList()));
            resourceLists.put("modules", resources.modules().stream().map(Module::getName).collect(Collectors.toList()));

            parameterValues.put(resources.getCourse().getName(), resourceLists);
            courses.add(resources.getCourse().getName());
        }

        JsonArray results = tasks.stream()
                .map(task->makeTaskInstances( //Make 5 task instances for each task.
                        config.getInteger(EvaluationTaskGenerationRequestFields.NUM_INSTANCES_PER_TASK.field()),
                        task,
                        config.getJsonArray(EvaluationTaskGenerationRequestFields.LOGIN_TEMPLATES.field()),
                        courses,
                        parameterValues
                ))
                .collect(JsonArray::new, JsonArray::addAll, JsonArray::addAll);

        promise.complete(results);
    }

    private JsonArray makeTaskInstances(int numInstances, JsonObject taskDetails, JsonArray loginTemplates, List<String> courses, Map<String,Map<String,List<String>>> courseData){

        JsonArray result = new JsonArray();

        int i = 0;
        while (i < numInstances){
            result.add(makeTaskInstance(taskDetails, loginTemplates, courses, courseData));
            i++;
        }

        return result;

    }

    /**
     *
     * @param taskDetails A json object with 'task', 'id', 'parameters', and 'template' fields.
     * @param loginTemplates
     * @return a JSON object containing a WebVoyager formatted task and an Odobot formatted task under the fields 'webVoyager','odoBot' and 'odoBotNL' respectively.
     */
    private JsonObject makeTaskInstance(JsonObject taskDetails, JsonArray loginTemplates, List<String> courses, Map<String,Map<String,List<String>>> parameterValues){

        JsonObject result = new JsonObject();

        //Resolve template parameters
        //Get the parameters we will need values for.
        JsonArray taskParameters = taskDetails.getJsonArray("parameters");
        //Get the values for the parameters.
        JsonObject values = getParameterValues(courses, taskParameters, parameterValues);

        //Establish a task id that is shared for equivalent tasks between odobot and webvoyager.
        UUID taskId = UUID.randomUUID();

        //Get the model mappings for this task
        JsonObject modelMapping = taskDetails.getJsonObject("model_mapping");

        String evalId = taskDetails.getString("id") + "|OdoBot|" + taskId.toString();

        result.put("webVoyager", makeWebVoyagerTask(taskDetails, loginTemplates, values, taskId));
        //ASE 2025 paper compares vs OdoBotNL
        //result.put("odoBot", makeOdoBotTask(values, modelMapping, taskId ).put("_evalId", evalId));
        result.put("odoBotNL", makeOdoBotNLTask(result.getJsonObject("webVoyager"), evalId, taskId));


        return result;
    }

    /**
     * Create a natural language task for OdoBot. These are built from webvoyager tasks.
     * @param webVoyagerTask
     * @return
     */
    private JsonObject makeOdoBotNLTask(JsonObject webVoyagerTask, String evalId, UUID id){
        JsonObject result = new JsonObject()
                .put("id", id.toString())
                .put("_evalId", evalId)
                .put("userLocation", config.getString(EvaluationTaskGenerationRequestFields.STARTING_USER_LOCATION.field()))
                .put("task", webVoyagerTask.getString("ques"));

        return result;
    }


    private OdoBotExecutionRequest makeOdoBotTask(JsonObject values, JsonObject modelMapping, UUID taskId){

        OdoBotExecutionRequest request = new OdoBotExecutionRequest();
        request.id(taskId);
        request.target(modelMapping.getString("target"));
        request.userLocation(config.getString(EvaluationTaskGenerationRequestFields.STARTING_USER_LOCATION.field()));

        //Add input parameters for the username and password
        request.addInputParameter(config.getString(EvaluationTaskGenerationRequestFields.USERNAME_PARAM_ID.field()), config.getString(EvaluationTaskGenerationRequestFields.TARGET_APP_USERNAME.field));
        request.addInputParameter(config.getString(EvaluationTaskGenerationRequestFields.PASSWORD_PARAM_ID.field()), config.getString(EvaluationTaskGenerationRequestFields.TARGET_APP_PASSWORD.field()));

        //Include 'text-entry-checkbox' parameter where appropriate
        if(modelMapping.containsKey("text-entry-checkbox")){
            request.addInputParameter(modelMapping.getString("text-entry-checkbox"), "true");
        }

        //Resolve the rest of the task parameters.
        values.forEach(entry->{
            String _value = (String)entry.getValue();
            switch (entry.getKey()){
                case "course":
                    request.addSchemaParameter(modelMapping.getString("course"), _value);
                    break;
                case "text-entry-checkbox":
                    request.addInputParameter(modelMapping.getString("text-entry-checkbox"), _value);
                    break;
                case "assignment-title":
                    request.addInputParameter(modelMapping.getString("assignment-title"), _value);
                    break;
                case "quiz-title":
                    request.addInputParameter(modelMapping.getString("quiz-title"), _value);
                    break;
                case "page-title":
                    request.addInputParameter(modelMapping.getString("page-title"), _value);
                    break;
                case "module-name":
                    request.addInputParameter(modelMapping.getString("module-name"), _value);
                    break;
                case "old-quiz-title":
                    request.addSchemaParameter(modelMapping.getString("old-quiz-title"), _value);
                    break;
                case "new-quiz-title":
                    request.addInputParameter(modelMapping.getString("new-quiz-title"), _value);
                    break;
                case "old-page-title":
                    request.addSchemaParameter(modelMapping.getString("old-page-title"), _value);
                    break;
                case "new-page-title":
                    request.addInputParameter(modelMapping.getString("new-page-title"), _value);
                    break;
                case "old-module-name":
                    request.addSchemaParameter(modelMapping.getString("old-module-name"),_value);
                    break;
                case "new-module-name":
                    request.addInputParameter(modelMapping.getString("new-module-name"), _value);
                    break;
                case "old-assignment-title":
                    request.addSchemaParameter(modelMapping.getString("old-assignment-title"), _value);
                    break;
                case "new-assignment-title":
                    request.addInputParameter(modelMapping.getString("new-assignment-title"), _value);
                    break;
                case "assignment":
                    request.addSchemaParameter(modelMapping.getString("assignment"), _value);
                    break;
                case "page":
                    request.addSchemaParameter(modelMapping.getString("page"), _value);
                    break;
                default:
                    log.warn("Encountered UNKNOWN PARAMETER {} while trying to generate OdoBotExecutionRequest", entry.getKey());
            }

        });

        return request;
    }

    private JsonObject makeWebVoyagerTask(JsonObject taskDetails, JsonArray loginTemplates,JsonObject values, UUID taskId){

        JsonObject result = new JsonObject();
        result.put("web", this.appUrl);
        result.put("web_name", this.appName);
        result.put("description", taskDetails.getString("task"));

        String webVoyagerTaskId = taskDetails.getString("id") + "|WebVoyager|" + taskId.toString();
        result.put("id", webVoyagerTaskId);

        StringBuilder sb = new StringBuilder();
        //Inject the login instruction.
        sb.append(loginTemplates.getString(random.nextInt(loginTemplates.size())).replace("<username>", this.username).replace("<password>", this.password)+ " ");


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

    /**
     * Compute parameter values from the course data. If a course has insufficient data to satisfy the task, a new course is chosen.
     * @param courses A list of courses whose data is included in the parameterValues
     * @param parameters A JsonArray of strings containing the task parameter values.
     * @param parameterValues A Map containing sample parameter values for all the courses given in the courses param
     * @return A JsonObject with values for each parameter given in parameters.
     */
    private JsonObject getParameterValues(List<String> courses, JsonArray parameters,  Map<String,Map<String,List<String>>> parameterValues){
        //Select a course from which to populate the parameters
        String chosenCourse = courses.get(random.nextInt(courses.size()));
        //Retrieve the resource values for that course.
        Map<String,List<String>> courseValues = parameterValues.get(chosenCourse);
        //Get values for those parameters from the available course values
        //Try and get the parameters that is, it is possible that the selected course is out of valid values.
        try{
            JsonObject values = getRandomParameterValues(parameters, chosenCourse, courseValues);
            return values;
        }catch (IndexOutOfBoundsException e){
            //Compute a new list of courses, excluding the one that caused the IndexOutOfBounds exception.
            courses = courses.stream().filter(course->!course.equals(chosenCourse)).collect(Collectors.toList());

            if(courses.size() == 0){
                throw new RuntimeException("Out of valid course materials for parameter values!");
            }

            //Call this method again, with a new course.
            return getParameterValues(courses, parameters, parameterValues);
        }
    }

    private JsonObject getRandomParameterValues(JsonArray parameters, String course, Map<String,List<String>> parameterValues){

        JsonObject result = new JsonObject();

        var _parameters = parameters.stream().map(p->(String)p).toList();
        for(String param: _parameters){
            try{
                result.put(param, getRandomParameterValue(param, course, parameterValues));
            }catch (IndexOutOfBoundsException e){
                throw e;
            }
        };

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
            case "text-entry-checkbox" -> "true";
            case "assignment-title" -> parameterValues.get("assignments").remove(0);
            case "quiz-title" -> parameterValues.get("quizzes").remove(0);
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
