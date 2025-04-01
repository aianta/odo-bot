package ca.ualberta.odobot.explorer;

import ca.ualberta.odobot.common.HttpServiceVerticle;
import ca.ualberta.odobot.taskplanner.TaskPlannerService;
import io.reactivex.rxjava3.core.Completable;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.AbstractVerticle;
import io.vertx.rxjava3.core.http.HttpServer;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.rxjava3.ext.web.handler.BodyHandler;
import io.vertx.rxjava3.ext.web.handler.LoggerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ListIterator;
import java.util.Optional;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.TASK_PLANNER_SERVICE_ADDRESS;

/**
 * @author Alexandru Ianta
 * A wrapper verticle for the Odo Explorer, a selenium based tool which
 * explores a web application while running the Odo Sight extension in
 * order to generate data that can be used to train TPG.
 */
public class ExplorerVerticle extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(ExplorerVerticle.class);

    private static TaskPlannerService taskPlannerService;

    public String serviceName(){return "Data Generation (Explorer) Service";}

    public String configFilePath(){
        return "config/explorer.yaml";
    }


    public Completable onStart(){
        super.onStart();
        try{

            //Init task planner service proxy
            taskPlannerService = TaskPlannerService.createProxy(vertx.getDelegate(), TASK_PLANNER_SERVICE_ADDRESS);

            api.route(HttpMethod.POST, "/explore").handler(this::exploreValidationHandler);
            api.route(HttpMethod.POST, "/explore").handler(this::exploreHandler);

            api.route(HttpMethod.POST, "/plan").handler(this::planValidationHandler);
            api.route(HttpMethod.POST, "/plan").handler(this::planHandler);

            api.route(HttpMethod.POST, "/evaluationTasks").handler(this::evaluationTaskValidationHandler);
            api.route(HttpMethod.POST, "/evaluationTasks").handler(this::evaluationTasksHandler);

            api.route(HttpMethod.POST, "/convertToOdoBotNL").handler(this::convertToOdoBotNL);

            api.route(HttpMethod.POST, "/evaluate").handler(this::evaluateValidationHandler);
            api.route(HttpMethod.POST, "/evaluate").handler(this::evaluateHandler);

            api.route(HttpMethod.POST, "/evaluationResults").handler(this::computeEvaluationResults);
            //TODO: Refactor these handlers, they should be a single handler, and the validation logic should probably be encapsulated in some other class.
            api.route(HttpMethod.POST, "/evaluationResultsWebVoyager").handler(this::computeEvaluationResultsWebVoyager);

            api.route(HttpMethod.POST, "/filterTasks").handler(rc->{
                JsonArray input = rc.body().asJsonArray();
                String outputType = rc.request().getParam("output");
                JsonArray output = input.stream().map(o->(JsonObject)o)
                        .map(json->json.getJsonObject(outputType))
                        .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
               rc.response().setStatusCode(200).end(output.encodePrettily());
            });


        }catch (Exception e){
            log.error(e.getMessage(), e);
        }

        return Completable.complete();

    }

    /**
     * Converts a list of evaluation tasks to OdoBotNL format.
     * @param rc
     */
    private void convertToOdoBotNL(RoutingContext rc){

        //Let the user specify whether or not the odoBotNL tasks should be merged into the provided input tasks.
        //If false the output will be a json array of just odoBotNL tasks.
        boolean merge = Boolean.parseBoolean(rc.request().getParam("merge", "true"));
        JsonArray input = rc.body().asJsonArray();


        JsonArray nlTasks = input.stream()
                .map(o->(JsonObject)o)
                .map(taskDefinition->{

                    //Modify the evalId to distinguish OdoBotNL tasks
                    String evalId = taskDefinition.getJsonObject("odoBot").getString("_evalId");
                    String [] split = evalId.split("\\|");
                    split[1] = "OdoBotNL";
                    evalId = split[0] +"|"+ split[1] + "|"+ split[2];

                    JsonObject nlTask = new JsonObject()
                            .put("id", taskDefinition.getJsonObject("odoBot").getString("id"))
                            .put("_evalId", evalId)
                            .put("userLocation", taskDefinition.getJsonObject("odoBot").getString("userLocation"))
                            .put("task", taskDefinition.getJsonObject("webVoyager").getString("ques"));

                    return nlTask;
                }).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

        if(merge){
            JsonArray output = new JsonArray();

            for(int i = 0; i < input.size(); i++){
                JsonObject webVoyagerTask = input.getJsonObject(i).getJsonObject("webVoyager");
                JsonObject odoBotTask = input.getJsonObject(i).getJsonObject("odoBot");
                JsonObject odoBotNLTask = nlTasks.getJsonObject(i);

                JsonObject mergedResult = new JsonObject()
                        .put("webVoyager", webVoyagerTask)
                        .put("odoBot", odoBotTask)
                        .put("odoBotNL", odoBotNLTask);
                output.add(mergedResult);
            }

            rc.response().setStatusCode(200).end(output.encodePrettily());
        }else{
            rc.response().setStatusCode(200).end(nlTasks.encodePrettily());
        }

    }

    private void computeEvaluationResultsWebVoyager(RoutingContext rc){
        JsonObject request = rc.body().asJsonObject();

        String pathToResults = request.getString("results_path");
        JsonArray webVoyagerTasks = getTasks(request.getJsonArray("tasks"), Agent.WEB_VOYAGER);
        //Still need the OdoBot tasks because the way the parameters are split up there makes it easier to evaluate.
        JsonArray odoBotTasks = getTasks(request.getJsonArray("tasks"), Agent.ODO_BOT);
        JsonObject courseIds = request.getJsonObject("course_ids");
        JsonObject assignmentIds = request.getJsonObject("assignment_ids");
        JsonObject quizIds = request.getJsonObject("quiz_ids");
        JsonObject pageSlugs = request.getJsonObject("page_slugs");
        JsonObject moduleIds = request.getJsonObject("module_ids");

        JsonObject results = new JsonObject();
        results.put("succeeded_tasks", new JsonArray());
        results.put("failed_tasks", new JsonArray());
        results.put("manifest", new JsonObject());

        try {


            File dir = new File(pathToResults);
            File[] contents = dir.listFiles();
            if (contents != null) {
                for (File _log : contents) {

                    if(_log.getName().contains("navpath") || _log.getName().contains("task-query") || _log.isDirectory()){
                        //Skip navpath and task query details
                        continue;
                    }

                    JsonObject webVoyagerTaskInfo = getWebVoyagerTaskByFileName(_log.getName(), webVoyagerTasks);
                    JsonObject taskInfo = getOdoBotTaskByFilename(_log.getName(), odoBotTasks);
                    String evalId = webVoyagerTaskInfo.getString("id");
                    JsonArray events = new JsonArray(new String(Files.readAllBytes(Path.of(_log.getPath()))));

                    boolean success = switch (getTaskNumber(evalId)){
                        case 1 ->{
                            /**
                             * Create a blank quiz task
                             */

                            //Get the target course for the task
                            JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(2);
                            String targetCourseName = courseParameter.getString("query");

                            yield urlMatches(events,"http://localhost:8088/courses/%s/quizzes/new?fresh=1".formatted(courseIds.getString(targetCourseName)), "POST");
                        }
                        case 2 ->{
                            /**
                             * This is the create a new assignment task. We verify this one by ensuring
                             * there exists a network event creating an assignment in the expected course.
                             */
                            String newAssignmentName = taskInfo.getJsonArray("parameters").getJsonObject(3).getString("value");

                            JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(4);
                            String targetCourseName = courseParameter.getString("query");

                            yield urlMatches(events, "http://localhost:8088/api/v1/courses/%s/assignments".formatted(
                                    courseIds.getString(targetCourseName)),"POST");
                        }
                        case 3 ->{
                            JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(3);
                            String targetCourseName = courseParameter.getString("query");

                            yield urlMatches(events, "http://localhost:8088/api/v1/courses/%s/pages".formatted(
                                    courseIds.getString(targetCourseName)), "POST");
                        }
                        case 4 ->{
                            /**
                             * Create a new module task
                             */
                            String newModuleName = taskInfo.getJsonArray("parameters").getJsonObject(2).getString("value");

                            JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(3);
                            String targetCourseName = courseParameter.getString("query");

                            yield urlMatches(events, "http://localhost:8088/courses/%s/modules".formatted(
                                    courseIds.getString(targetCourseName)
                            ), "POST") && requestPostDataContainsKeyWithValue(events, "context_module[name]", newModuleName);
                        }
                        case 5 -> {
                            /**
                             * Edit quiz title
                             */
                            JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(4);
                            String targetCourseName = courseParameter.getString("query");

                            JsonObject quizParameter = taskInfo.getJsonArray("parameters").getJsonObject(2);
                            String targetQuizName = quizParameter.getString("query");

                            yield urlMatches(events, "http://localhost:8088/courses/%s/quizzes/%s".formatted(
                                    courseIds.getString(targetCourseName),
                                    quizIds.getString(targetQuizName)
                            ), "POST");
                        }
                        case 6 ->{
                            /**
                             * Edit a page title
                             */
                            JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(4);
                            String targetCourseName = courseParameter.getString("query");

                            JsonObject pageParameter = taskInfo.getJsonArray("parameters").getJsonObject(2);
                            String targetPageName = pageParameter.getString("query");

                            yield urlMatches(events, "http://localhost:8088/api/v1/courses/%s/pages/%s".formatted(
                                    courseIds.getString(targetCourseName),
                                    pageSlugs.getString(targetPageName)
                            ), "PUT");
                        }
                        case 7->{
                            /**
                             * Edit a module title
                             */

                            JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(4);
                            String targetCourseName = courseParameter.getString("query");

                            JsonObject moduleParameter = taskInfo.getJsonArray("parameters").getJsonObject(2);
                            String targetModuleName = moduleParameter.getString("query");

                            yield urlMatches(events, "http://localhost:8088/courses/%s/modules/%s".formatted(
                                    courseIds.getString(targetCourseName),
                                    moduleIds.getString(targetModuleName)
                            ), "POST");
                        }
                        case 8 ->{
                            /**
                             * Edit an assignment title.
                             */
                            String editedTitle = taskInfo.getJsonArray("parameters").getJsonObject(3).getString("value");

                            JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(4);
                            String targetCourseName = courseParameter.getString("query");

                            JsonObject assignmentParameter = taskInfo.getJsonArray("parameters").getJsonObject(2);
                            String targetAssignmentName = assignmentParameter.getString("query");

                            yield urlMatches(events, "http://localhost:8088/api/v1/courses/%s/assignments/%s".formatted(
                                    courseIds.getString(targetCourseName),
                                    assignmentIds.getString(targetAssignmentName)
                            ), "PUT") && responseBodyContainsKeyWithValue(events, "name", editedTitle);
                        }
                        case 9 ->{
                            /**
                             * Delete an assignment
                             */
                            JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(3);
                            String targetCourseName = courseParameter.getString("query");

                            JsonObject assignmentParameter = taskInfo.getJsonArray("parameters").getJsonObject(2);
                            String targetAssignmentName = assignmentParameter.getString("query");

                            yield urlMatches(events, "http://localhost:8088/api/v1/courses/%s/assignments/%s".formatted(
                                    courseIds.getString(targetCourseName),
                                    assignmentIds.getString(targetAssignmentName)
                            ), "DELETE");
                        }
                        case 10 ->{
                            JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(3);
                            String targetCourseName = courseParameter.getString("query");

                            JsonObject pageParameter = taskInfo.getJsonArray("parameters").getJsonObject(2);
                            String targetPageName = pageParameter.getString("query");

                            yield urlMatches(events, "http://localhost:8088/api/v1/courses/%s/pages/%s".formatted(
                                    courseIds.getString(targetCourseName),
                                    pageSlugs.getString(targetPageName)
                            ),  "DELETE" );
                        }
                        default -> throw new RuntimeException("Unknown task type!");
                    };

                    //Add the verification result to the manifest
                    results.getJsonObject("manifest").put(evalId, success);

                    if(success){
                        results.getJsonArray("succeeded_tasks").add(evalId);
                    }else{
                        results.getJsonArray("failed_tasks").add(evalId);
                    }
                }
            }

            results.put("succeeded", results.getJsonArray("succeeded_tasks").size());
            results.put("failed", results.getJsonArray("failed_tasks").size());
            results.put("total", results.getJsonObject("manifest").size());


            rc.response().setStatusCode(200).end(results.encodePrettily());

        }catch (IOException e){
            log.error(e.getMessage(), e);
        }

    }

    private boolean responseBodyContainsKeyWithValue(JsonArray events, String key, String expectedValue){
        return events.stream().map(o->(JsonObject)o)
                //Only consider response bodies that are parsable.
                .filter(event->{

                    try{
                        JsonObject responseBody = event.getJsonObject("responseBody");
                        return true;
                    }catch (ClassCastException e){
                        try{
                            new JsonObject(event.getString("responseBody"));
                            return true;
                        }catch (DecodeException e1){
                            return false;
                        }
                    }
                })
                .map(event->{
                    JsonObject responseBody;
                    try{
                        responseBody = event.getJsonObject("responseBody");
                    }catch (ClassCastException e){
                        responseBody = new JsonObject(event.getString("responseBody"));
                    }

                    if(responseBody.containsKey("body")){
                        return new JsonObject(responseBody.getString("body"));
                    }else {
                        return responseBody;
                    }
                })
                .filter(event->event.getString(key).equals(expectedValue))
                .findFirst().isPresent();
    }

    /**
     * Checks for a network request with POST data in URL encoding. If that exists, decodes it
     * and looks for a particular key value pair.
     * Returns true if a network event containing post data that contains the expected key with the expected value
     * @param events
     * @param key
     * @param expectedValue
     * @return
     */
    private boolean requestPostDataContainsKeyWithValue(JsonArray events, String key, String expectedValue){
        return events.stream().map(o->(JsonObject)o)
                .filter(event->{
                    if(event.getJsonObject("params").getJsonObject("request").getBoolean("hasPostData")){
                        String encoded = event.getJsonObject("params").getJsonObject("request").getString("postData");
                        try{
                            String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
                            String split [] = decoded.split("&");
                            Optional<String> _target = Arrays.stream(split).filter(s->s.contains(key)).findFirst();
                            if(!_target.isPresent()){
                                return false;
                            }

                            String target = _target.get();
                            target = target.split("=")[1];

                            return target.equals(expectedValue);

                        }catch (UnsupportedEncodingException e){
                            log.error(e.getMessage(),e);
                        }

                    }

                    return false;

                }
                ).findFirst().isPresent();
    }

    /**
     * Assumes WebVoyager event format.
     * @param events
     * @param expectedUrl
     * @param expectedMethod
     * @return true if one of the events in the provided json array is a network request matching the provided url.
     */
    private boolean urlMatches(JsonArray events, String expectedUrl, String expectedMethod){
        return events.stream().map(o->(JsonObject)o)
                .filter(event-> event.getJsonObject("params").getJsonObject("request").getString("url")
                        .equals(expectedUrl) &&
                        event.getJsonObject("params").getJsonObject("request").getString("method").equals(expectedMethod)
                ).findFirst().isPresent();
    }



    private void computeEvaluationResults(RoutingContext rc)  {

        JsonObject request = rc.body().asJsonObject();

        String pathToResults = request.getString("results_path");
        JsonArray odoBotTasks = getTasks(request.getJsonArray("tasks"), Agent.ODO_BOT);
        JsonObject courseIds = request.getJsonObject("course_ids");
        JsonObject assignmentIds = request.getJsonObject("assignment_ids");
        JsonObject quizIds = request.getJsonObject("quiz_ids");
        JsonObject pageSlugs = request.getJsonObject("page_slugs");
        JsonObject moduleIds = request.getJsonObject("module_ids");

        JsonObject results = new JsonObject();
        results.put("succeeded_tasks", new JsonArray());
        results.put("failed_tasks", new JsonArray());
        results.put("manifest", new JsonObject());

        try{


        File dir = new File(pathToResults);
        File[] contents = dir.listFiles();
        if(contents != null){
            for(File _log: contents){

                if(_log.getName().contains("navpath") || _log.getName().contains("task-query") || _log.isDirectory() || _log.getName().contains(".yaml")){
                    //Skip navpath and task query logs
                    continue;
                }

                JsonObject taskInfo = getOdoBotTaskByFilename(_log.getName(), odoBotTasks);
                String evalId = taskInfo.getString("_evalId");
                JsonArray events = new JsonArray(new String(Files.readAllBytes(Path.of(_log.getPath()))));

                boolean success = switch (getTaskNumber(evalId)){
                    case 1 -> {
                        /**
                         * This is the create quiz task. We verify this one was done correctly by ensuring
                         * there exists an appropriate network event creating a quiz in the expected course.
                         */

                        //Get the target course for the task
                        JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(2);
                        String targetCourseName = courseParameter.getString("query");


                        Optional<JsonObject> networkEvent = events.stream().map(o -> (JsonObject) o)
                                .filter(event -> event.getJsonObject("eventDetails").getString("name").equals("NETWORK_EVENT") &&
                                        event.getJsonObject("eventDetails").getString("method").equals("POST") &&
                                        event.getJsonObject("eventDetails").getString("url")
                                                .equals("http://localhost:8088/courses/%s/quizzes/new?fresh=1".formatted(
                                                        courseIds.getString(targetCourseName)
                                                ))
                                ).findFirst();

                        yield networkEvent.isPresent();
                    }
                    case 2 -> {
                        /**
                         * This is the create a new assignment task. We verify this one by ensuring
                         * there exists a network event creating an assignment in the expected course.
                         */
                        String newAssignmentName = taskInfo.getJsonArray("parameters").getJsonObject(3).getString("value");

                        JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(4);
                        String targetCourseName = courseParameter.getString("query");

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event -> event.getString("name").equals("NETWORK_EVENT") &&
                                        event.getString("method").equals("POST") &&
                                        event.getString("url")
                                                .equals("http://localhost:8088/api/v1/courses/%s/assignments".formatted(
                                                        courseIds.getString(targetCourseName)
                                                ))
                                        //&& new JsonObject(event.getString("responseBody")).getString("name").equals(newAssignmentName)

                                ).findFirst();

                        yield networkEvent.isPresent();

                    }
                    case 3 -> {
                        /**
                         * This is the create page task.
                         */
                        String newPageName = taskInfo.getJsonArray("parameters").getJsonObject(2).getString("value");

                        JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(3);
                        String targetCourseName = courseParameter.getString("query");

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT") &&
                                        event.getString("method").equals("POST") &&
                                        event.getString("url").equals("http://localhost:8088/api/v1/courses/%s/pages".formatted(
                                                courseIds.getString(targetCourseName)
                                        )) &&
                                        new JsonObject(event.getString("responseBody")).getString("title").equals(newPageName)
                                ).findFirst();

                        yield networkEvent.isPresent();
                    }

                    case 4 ->{
                        /**
                         * Create a new module task
                         */
                        String newModuleName = taskInfo.getJsonArray("parameters").getJsonObject(2).getString("value");

                        JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(3);
                        String targetCourseName = courseParameter.getString("query");

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT") &&
                                        event.getString("method").equals("POST") &&
                                        event.getString("url").equals("http://localhost:8088/courses/%s/modules".formatted(
                                                courseIds.getString(targetCourseName)
                                        )) &&
                                        new JsonObject(event.getString("requestBody")).getJsonObject("formData").getJsonArray("context_module[name]").getString(0).equals(newModuleName)
                                        )
                                .findFirst();

                        yield networkEvent.isPresent();
                    }

                    case 5 ->{

                        /**
                         * Edit quiz title
                         */
                        String updatedQuizName = taskInfo.getJsonArray("parameters").getJsonObject(3).getString("value");

                        JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(4);
                        String targetCourseName = courseParameter.getString("query");

                        JsonObject quizParameter = taskInfo.getJsonArray("parameters").getJsonObject(2);
                        String targetQuizName = quizParameter.getString("query");

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT"))
                                .filter(event->event.getString("method").equals("POST"))
                                .filter(event->
                                        event.getString("url").equals("http://localhost:8088/courses/%s/quizzes/%s".formatted(
                                                courseIds.getString(targetCourseName),
                                                quizIds.getString(targetQuizName)
                                        )) &&
                                        new JsonObject(event.getString("responseBody")).getJsonObject("quiz").getString("title").equals(updatedQuizName)
                                        ).findFirst();

                        yield networkEvent.isPresent();
                    }
                    case 6 ->{
                        /**
                         *  Edit a page title
                         */
                        String updatedPageName = taskInfo.getJsonArray("parameters").getJsonObject(3).getString("value");

                        JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(4);
                        String targetCourseName = courseParameter.getString("query");

                        JsonObject pageParameter = taskInfo.getJsonArray("parameters").getJsonObject(2);
                        String targetPageName = pageParameter.getString("query");

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT") &&
                                        event.getString("method").equals("PUT") &&
                                        event.getString("url").equals("http://localhost:8088/api/v1/courses/%s/pages/%s".formatted(
                                                courseIds.getString(targetCourseName),
                                                pageSlugs.getString(targetPageName)
                                        )) &&
                                        new JsonObject(event.getString("responseBody")).getString("title").equals(updatedPageName)
                                ).findFirst();

                        yield networkEvent.isPresent();
                    }
                    case 7 ->{

                        /**
                         *  Edit a module title
                         */
                        String updatedName = taskInfo.getJsonArray("parameters").getJsonObject(3).getString("value");

                        JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(4);
                        String targetCourseName = courseParameter.getString("query");
                        log.info("[{}]targetCourseName: [{}]{}", evalId, courseIds.getString(targetCourseName), targetCourseName);

                        JsonObject moduleParameter = taskInfo.getJsonArray("parameters").getJsonObject(2);
                        String targetModuleName = moduleParameter.getString("query");
                        log.info("[{}]targetModuleName: [{}]{}", evalId, moduleIds.getString(targetModuleName), targetModuleName);

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT"))
                                .filter(event->event.getString("method").equals("POST"))
                                .filter(event->
                                        event.getString("url").equals("http://localhost:8088/courses/%s/modules/%s".formatted(
                                                courseIds.getString(targetCourseName),
                                                moduleIds.getString(targetModuleName)
                                        )))
                                .filter(event->event.containsKey("responseBody")?
                                        //Verify that the correct name was entered
                                        new JsonObject(event.getString("responseBody"))
                                        .getJsonObject("context_module")
                                        .getString("name").equals(updatedName):true


                                ).findFirst();

                        yield networkEvent.isPresent();

                    }
                    case 8 ->{
                        /**
                         *  Edit an assignment title.
                         */
                        String editedTitle = taskInfo.getJsonArray("parameters").getJsonObject(3).getString("value");

                        JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(4);
                        String targetCourseName = courseParameter.getString("query");

                        JsonObject assignmentParameter = taskInfo.getJsonArray("parameters").getJsonObject(2);
                        String targetAssignmentName = assignmentParameter.getString("query");

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT"))
                                .filter(event->event.getString("method").equals("PUT"))
                                .filter(event->event.getString("url").equals("http://localhost:8088/api/v1/courses/%s/assignments/%s".formatted(
                                                courseIds.getString(targetCourseName),
                                                assignmentIds.getString(targetAssignmentName)
                                        )))
                                .filter(event->
                                        event.containsKey("responseBody")?new JsonObject(event.getString("responseBody")).getString("name").equals(editedTitle):true
                                ).findFirst();

                        yield networkEvent.isPresent();

                    }
                    case 9 ->{
                        /**
                         *   Delete an assignment
                         */

                        JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(3);
                        String targetCourseName = courseParameter.getString("query");

                        JsonObject assignmentParameter = taskInfo.getJsonArray("parameters").getJsonObject(2);
                        String targetAssignmentName = assignmentParameter.getString("query");

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT") &&
                                        event.getString("method").equals("DELETE") &&
                                        event.getString("url").equals("http://localhost:8088/api/v1/courses/%s/assignments/%s".formatted(
                                                courseIds.getString(targetCourseName),
                                                assignmentIds.getString(targetAssignmentName)
                                        ))).findFirst();

                        yield networkEvent.isPresent();
                    }
                    case 10 ->{
                        /**
                         * Delete a page
                         */
                        JsonObject courseParameter = taskInfo.getJsonArray("parameters").getJsonObject(3);
                        String targetCourseName = courseParameter.getString("query");

                        JsonObject pageParameter = taskInfo.getJsonArray("parameters").getJsonObject(2);
                        String targetPageName = pageParameter.getString("query");

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT") &&
                                        event.getString("method").equals("DELETE") &&
                                        event.getString("url").equals("http://localhost:8088/api/v1/courses/%s/pages/%s".formatted(
                                                courseIds.getString(targetCourseName),
                                                pageSlugs.getString(targetPageName)
                                        ))).findFirst();

                        yield networkEvent.isPresent();

                    }
                    default ->{
                        throw new RuntimeException("Unknown task type!");
                    }
                };

                //Add the verification result to the manifest
                results.getJsonObject("manifest").put(evalId, success);

                if(success){
                    results.getJsonArray("succeeded_tasks").add(evalId);
                }else{
                    results.getJsonArray("failed_tasks").add(evalId);
                }
            }
        }

        results.put("succeeded", results.getJsonArray("succeeded_tasks").size());
        results.put("failed", results.getJsonArray("failed_tasks").size());
        results.put("total", results.getJsonObject("manifest").size());


        rc.response().setStatusCode(200).end(results.encodePrettily());

        }catch (IOException e){
            log.error(e.getMessage(),e);
        }


    }


    private int getTaskNumber(String evalId){
        String [] split = evalId.split("\\|");
        return Integer.parseInt(split[0]);
    }

    private JsonObject getWebVoyagerTaskByFileName(String filename, JsonArray tasks){
        return tasks.stream()
                .map(o->(JsonObject)o)
                /**
                 * task id is of the format <task number> | <agent> | <uuid>
                 * so task.getString("id").split("\\|")[2] yields the uuid which we expect to see in the filename of the matching file.
                 */
                .filter(task->filename.contains(task.getString("id").split("\\|")[2]))
                .findFirst()
                .get();
    }
    private JsonObject getOdoBotTaskByFilename(String filename, JsonArray tasks){
        log.info("{}", filename);
        return tasks.stream()
                .map(o->(JsonObject)o)
                .filter(task->filename.contains(task.getString("id")))
                .findFirst()
                .get();
    }

    private JsonObject getOdoBotTaskByEvalId(String evalId, JsonArray tasks){

        return tasks.stream()
                .map(o->(JsonObject)o)
                .filter(task->task.getString("_evalId").equals(evalId))
                .findFirst().get();

    }

    private void evaluateValidationHandler(RoutingContext rc){

        if(rc.body().available()){
            JsonObject config = rc.body().asJsonObject();
            validateFields(rc, config, EvaluationTaskRequestFields.values());

            if(!rc.response().ended()){
                rc.put("config", config);
                rc.next();
            }
        }else {
            rc.response().setStatusCode(400).end("Evaluate request was malformed or missing body.");
        }

    }

    private void evaluateHandler(RoutingContext rc){
        JsonObject config = rc.get("config");
        if(config == null){
            config = rc.body().asJsonObject();
        }

        String _agent = rc.request().getParam("agent", "odoBot");
        Agent agent = Agent.fromField(_agent);

        JsonArray tasks = getTasks(config.getJsonArray("tasks"), agent);


        Future f = null;

        ListIterator<JsonObject> taskIterator = tasks.stream().map(o->(JsonObject)o).collect(Collectors.toList()).listIterator();
        while (taskIterator.hasNext()){
            JsonObject _task = taskIterator.next();

            if(f == null){
                Promise<Void> promise = Promise.promise();
                promise.future()
                        .onFailure(err->serverError(rc, err));
                f = promise.future();

                EvaluateTask evaluateTask = new EvaluateTask(config, _task, promise, taskPlannerService, agent);
                Thread thread = new Thread(evaluateTask);
                thread.start();
            }else{
                JsonObject finalConfig = config;
                f = f.compose(fDone->{

                    Promise<Void> promise = Promise.promise();
                    promise.future()
                            .onFailure(err->serverError(rc, err));

                    EvaluateTask evaluateTask = new EvaluateTask(finalConfig, _task, promise, taskPlannerService, agent);
                    Thread thread = new Thread(evaluateTask);
                    thread.start();

                    return promise.future();
                });
            }


        }

        f.onSuccess(done->rc.response().setStatusCode(200).end());
    }

    /**
     * Helper method that returns a json array of just OdoBot tasks from a
     * json array that contains both odobot and webvoyager tasks.
     * @param tasks
     * @param agent The format of the task to retrieve either 'odoBot' or 'webVoyager'
     * @return
     */
    private JsonArray getTasks(JsonArray tasks, Agent agent){
        JsonArray result = tasks.stream()
                .map(o->(JsonObject)o)
                .map(task->task.getJsonObject(agent.taskField))
                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

        return result;
    }

    private void exploreValidationHandler(RoutingContext rc){
        if(rc.body().available()){
            JsonObject config = rc.body().asJsonObject();

            validateFields(rc, config, ExploreRequestFields.values());

            //If routing context wasn't ended during validation, let's proceed.
            if(!rc.response().ended()){
                rc.put("exploreConfig", config);
                rc.next();
            }

        }else{
            rc.response().setStatusCode(400).end("Explore request did not contain expected ExploreTask configuration in JSON format in the body of the request.");
        }



    }

    private void exploreHandler(RoutingContext rc){
        JsonObject config = rc.get("exploreConfig");

        ExploreTask exploreTask = new ExploreTask(config);
        Thread thread = new Thread(exploreTask);
        thread.start();

        rc.response().setStatusCode(200).end("Exploring started!");


    }

    private void planValidationHandler(RoutingContext rc){
        //TODO - any validation logic that makes sense
        if(rc.body().available()){
            JsonObject config = rc.body().asJsonObject();

            validateFields(rc, config, PlanRequestFields.values());

            if(!rc.response().ended()){
                rc.put("planConfig", config);
                rc.next();
                return;
            }
        }
        rc.response().setStatusCode(400).end("Explore request did not contain expected PlanTask configuration in JSON format in the body of the request.");

    }

    private void planHandler(RoutingContext rc){

        JsonObject planConfig = rc.get("planConfig");

        Promise<JsonObject> promise = Promise.promise();
        promise.future()
                .onFailure(err->serverError(rc, err))
                .onSuccess(plan->rc.response().setStatusCode(200).end(plan.encode()));

        PlanTask planTask = new PlanTask(planConfig, promise);
        Thread thread = new Thread(planTask);
        thread.start();
    }

    private void evaluationTaskValidationHandler(RoutingContext rc){
        if(rc.body().available()){
            JsonObject config = rc.body().asJsonObject();
            validateFields(rc, config, EvaluationTaskGenerationRequestFields.values());

            if(!rc.response().ended()){
                rc.put("config", config);
                rc.next();
                return;
            }
        }
        rc.response().setStatusCode(400).end("Evaluation Task Generation request was malformed. ");
    }

    private void evaluationTasksHandler(RoutingContext rc){
        JsonObject config = rc.get("config");
        if(config == null){
            config = rc.body().asJsonObject();
        }

        Promise<JsonArray> promise = Promise.promise();
        promise.future()
                .onFailure(err->serverError(rc, err))
                .onSuccess(results->rc.response().setStatusCode(200).end(results.encodePrettily()));

        EvaluationTaskGenerationTask task = new EvaluationTaskGenerationTask(config, promise);
        Thread thread = new Thread(task);
        thread.start();
    }

    private void serverError(RoutingContext rc, Throwable err){
        log.error(err.getMessage(), err);
        rc.response().setStatusCode(500).end(err.getMessage());
    }

    private void badRequest(RoutingContext rc, String message){
        rc.response().setStatusCode(400).end(message);
    }

    private <T extends RequestFields> void validateFields(RoutingContext rc, JsonObject config, T[] fieldsEnum){

        Arrays.stream(fieldsEnum).forEach(
                key->{
                    //Check that the fields exist
                    if(!config.containsKey(key.field())){
                        badRequest(rc, "Explore request body missing " + key.field() + " key.");
                    }

                    //Check that the string fields aren't blank
                    if(config.containsKey(key.field()) && key.type().equals(String.class) && config.getString(key.field()).isBlank()){
                        badRequest(rc, "Explore request body field "+key.field()+" cannot be empty." );
                    }
                }
        );


    }

}
