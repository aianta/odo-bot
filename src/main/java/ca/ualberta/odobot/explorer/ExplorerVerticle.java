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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
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

    /**
     * Return an entry from a JsonObject whose key is contained in a task description.
     * @param entries verification mapping entries usually of the form "<entity name>":"<id or slug>"
     * @param taskDescription the natural language description of a task.
     */
    private Map.Entry<String,Object> getEntryByTaskDescriptionContents(JsonObject entries, String taskDescription){
        log.info("entries: \n{}\n{}\n", entries.encodePrettily(), taskDescription);
        return entries.stream().filter(entry->taskDescription.contains(entry.getKey())).findFirst().get();

    }

    private String getItemByTaskDescriptionContents(JsonArray items, String taskDescription){
        return items.stream().map(o->(String)o).filter(s->taskDescription.contains(s)).findFirst().get();
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
        JsonArray quizzes = request.getJsonArray("quizzes"); //TODO verify quiz titles?
        JsonArray modules = request.getJsonArray("modules");
        JsonArray assignments = request.getJsonArray("assignments"); //TODO: verify assignment titles?
        JsonArray pages = request.getJsonArray("pages"); //TODO: verify page titles?

        JsonObject results = new JsonObject();
        results.put("succeeded_tasks", new JsonArray());
        results.put("failed_tasks", new JsonArray());
        results.put("manifest", new JsonObject());

        try {


            File dir = new File(pathToResults);
            File[] contents = dir.listFiles();
            if (contents != null) {
                for (File _log : contents) {

                    //Skip file during verification if
                    if(_log.getName().contains("navpath") || //It is a navpath log
                            _log.getName().contains("task-query") || //It is a task query log
                            _log.isDirectory() || //It is a directory
                            !Agent.isValidAgent(_log.getName()) ) //It does not contain a valid agent substring.
                    {
                        continue;
                    }

                    JsonObject webVoyagerTaskInfo = getWebVoyagerTaskByFileName(_log.getName(), webVoyagerTasks);
                    String evalId = webVoyagerTaskInfo.getString("id");
                    JsonArray events = new JsonArray(new String(Files.readAllBytes(Path.of(_log.getPath()))));
                    String taskDescription = webVoyagerTaskInfo.getString("ques");

                    String targetCourseName = getEntryByTaskDescriptionContents(courseIds, taskDescription).getKey();
                    JsonArray verificationDetails = new JsonArray();
                    verificationDetails.add("Task text: " + taskDescription);
                    verificationDetails.add("targetCourseName: " + targetCourseName);

                    boolean success = switch (getTaskNumber(evalId)){
                        case 1 ->{
                            /**
                             * Create a quiz task
                             */

                            String nameOfCreatedQuiz = getItemByTaskDescriptionContents(quizzes, taskDescription);

                            verificationDetails.add("expectedNameOfCreatedQuiz: '" + nameOfCreatedQuiz+"'");

                            Pattern saveQuizPattern = Pattern.compile("http:\\/\\/localhost:8088\\/courses\\/%s\\/quizzes\\/[0-9]+".formatted(courseIds.getString(targetCourseName)));
                            verificationDetails.add("saveQuizURLPattern: "+ saveQuizPattern.pattern());

                            boolean urlMatches = urlMatches(events, saveQuizPattern, "POST");

                            verificationDetails.add("event found matching save Quiz url pattern &  method? " + urlMatches );

                            boolean postDataMatches = false;
                            if(urlMatches){
                                postDataMatches = requestPostDataContainsKeyWithValue(events, saveQuizPattern, "POST", "title", nameOfCreatedQuiz);

                            }
                            verificationDetails.add("event found matching save Quiz pattern with expected quiz title in request? " + postDataMatches);
                            verificationDetails.add("verification result: " + (urlMatches && postDataMatches));

                            yield (urlMatches);
                            //yield urlMatches(events,"http://localhost:8088/courses/%s/quizzes/new?fresh=1".formatted(courseIds.getString(targetCourseName)), "POST");
                        }
                        case 2 ->{
                            /**
                             * This is the create a new assignment task. We verify this one by ensuring
                             * there exists a network event creating an assignment in the expected course.
                             */


                            String newAssignmentName = getItemByTaskDescriptionContents(assignments, taskDescription);
                            verificationDetails.add("expectedNewAssignmentName: '" + newAssignmentName+"'");

                            String newAssignmentURL = "http://localhost:8088/api/v1/courses/%s/assignments".formatted(
                                    courseIds.getString(targetCourseName));

                            verificationDetails.add("newAssignmentURL: " + newAssignmentURL);

                            boolean urlMatches = urlMatches(events, newAssignmentURL ,"POST");

                            verificationDetails.add("event found matching create new assignment url & method? " + urlMatches);
                            boolean postDataMatches = false;
                            if(urlMatches){
                                postDataMatches = requestPostDataJsonContainsKeyWithValue(events, newAssignmentURL, "POST", "name", newAssignmentName);

                            }
                            verificationDetails.add("postData json contains key [%s] with value: %s? %s ".formatted("name", newAssignmentName, Boolean.toString(postDataMatches)));

                            yield urlMatches;
                        }
                        case 3 ->{

                            String expectedPageName = getItemByTaskDescriptionContents(pages, taskDescription);
                            verificationDetails.add("Expected name of new page: "+ expectedPageName);

                            boolean urlMatches = urlMatches(events, "http://localhost:8088/api/v1/courses/%s/pages".formatted(
                                    courseIds.getString(targetCourseName)), "POST");

                            verificationDetails.add("event found matching create new page url & method? " + urlMatches);

                            yield urlMatches;
                        }
                        case 4 ->{
                            /**
                             * Create a new module task
                             */
                            String newModuleName = getItemByTaskDescriptionContents(modules, taskDescription);
                            verificationDetails.add("Expected new module name: '"+ newModuleName + "'");

                            String expectedUrl = "http://localhost:8088/courses/%s/modules".formatted(
                                    courseIds.getString(targetCourseName));
                            verificationDetails.add("create module url: " + expectedUrl);

                            boolean urlMatches = urlMatches(events,  expectedUrl, "POST");
                            verificationDetails.add("Found event matching create module url & method? " + urlMatches);

                            boolean postDataMatches = false;
                            if(urlMatches){
                                postDataMatches = requestPostDataContainsKeyWithValue(events, "context_module[name]", newModuleName);
                            }
                            verificationDetails.add("Found expected module name in postData? " + postDataMatches);

                            yield  urlMatches && postDataMatches;
                        }
                        case 5 -> {
                            /**
                             * Edit quiz title
                             */

                            String targetQuizName = getEntryByTaskDescriptionContents(quizIds, taskDescription).getKey();
                            verificationDetails.add("expectedTargetQuiz: " + targetQuizName);

                            String expectedUrl = "http://localhost:8088/courses/%s/quizzes/%s".formatted(
                                    courseIds.getString(targetCourseName),
                                    quizIds.getString(targetQuizName)
                            );
                            verificationDetails.add("edit quiz url: "+ expectedUrl);

                            boolean urlMatches =urlMatches(events, expectedUrl , "POST");

                            verificationDetails.add("found event matching edit quiz url & method? " + urlMatches);

                            yield urlMatches;
                        }
                        case 6 ->{
                            /**
                             * Edit a page title
                             */

                            String targetPageName = getEntryByTaskDescriptionContents(pageSlugs, taskDescription).getKey();
                            verificationDetails.add("expected target page name: "+ targetPageName);

                            String expectedUrl = "http://localhost:8088/api/v1/courses/%s/pages/%s".formatted(
                                    courseIds.getString(targetCourseName),
                                    pageSlugs.getString(targetPageName)
                            );
                            verificationDetails.add("edit page url: " + expectedUrl);

                            boolean urlMatches = urlMatches(events, expectedUrl, "PUT");

                            verificationDetails.add("Found event matching edit page url & method? " + urlMatches);

                            yield urlMatches;
                        }
                        case 7->{
                            /**
                             * Edit a module title
                             */

                            String targetModuleName = getEntryByTaskDescriptionContents(moduleIds, taskDescription).getKey();
                            verificationDetails.add("expected target module name: " + targetModuleName);

                            String expectedUrl = "http://localhost:8088/courses/%s/modules/%s".formatted(
                                    courseIds.getString(targetCourseName),
                                    moduleIds.getString(targetModuleName)
                            );
                            verificationDetails.add("Edit module url: " + expectedUrl);

                            boolean urlMatches = urlMatches(events, expectedUrl, "POST");
                            verificationDetails.add("Found event matching edit module url & method? " + urlMatches);

                            yield urlMatches;
                        }
                        case 8 ->{
                            /**
                             * Edit an assignment title.
                             */


                            String targetAssignmentName = getEntryByTaskDescriptionContents(assignmentIds, taskDescription).getKey();
                            verificationDetails.add("expected target assignment name: " + targetAssignmentName);

                            //TODO: hardcoded what edited titles can be, at the very least this should be refactored into a shared constant between this and EvaluationTaskGenerationTask.java
                            String editedTitle = "modified - " + targetAssignmentName;
                            verificationDetails.add("expected modified assignment name: " + editedTitle);

                            String expectedUrl = "http://localhost:8088/api/v1/courses/%s/assignments/%s".formatted(
                                    courseIds.getString(targetCourseName),
                                    assignmentIds.getString(targetAssignmentName)
                            );
                            verificationDetails.add("edit assignment url: " + expectedUrl);

                            boolean urlMatches = urlMatches(events, expectedUrl, "PUT");
                            verificationDetails.add("found event matching edit assignment url & method? " + urlMatches);

                            boolean postDataMatches = false;
                            if(urlMatches){
                                postDataMatches =  responseBodyContainsKeyWithValue(events, "name", editedTitle);
                            }
                            verificationDetails.add("found response body containing edited title? " + postDataMatches );

                            yield  urlMatches ;
                        }
                        case 9 ->{
                            /**
                             * Delete an assignment
                             */

                            String targetAssignmentName = getEntryByTaskDescriptionContents(assignmentIds, taskDescription).getKey();
                            verificationDetails.add("expected name of assignment to be deleted: '" + targetAssignmentName + "'");

                            String expectedUrl = "http://localhost:8088/api/v1/courses/%s/assignments/%s".formatted(
                                    courseIds.getString(targetCourseName),
                                    assignmentIds.getString(targetAssignmentName)
                            );
                            verificationDetails.add("delete assignment url: " + expectedUrl);

                            boolean urlMatches = urlMatches(events, expectedUrl , "DELETE");
                            verificationDetails.add("found event matching delete assignment url & method? " + urlMatches);

                            yield urlMatches;
                        }
                        case 10 ->{

                            String targetPageName = getEntryByTaskDescriptionContents(pageSlugs, taskDescription).getKey();
                            verificationDetails.add("expected name of page to be deleted: '" + targetPageName + "'");

                            String expectedUrl = "http://localhost:8088/api/v1/courses/%s/pages/%s".formatted(
                                    courseIds.getString(targetCourseName),
                                    pageSlugs.getString(targetPageName)
                            );
                            verificationDetails.add("delete page url: " + expectedUrl);

                            boolean urlMatches = urlMatches(events, expectedUrl ,  "DELETE" );
                            verificationDetails.add("found event matching delete page url? " + urlMatches);

                            yield urlMatches;
                        }
                        default -> throw new RuntimeException("Unknown task type!");
                    };

                    //Add the verification result to the manifest
                    results.getJsonObject("manifest").put(evalId, verificationDetails.size() > 0?verificationDetails:success);

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

    private Optional<JsonObject> getEventWhereUrlMatches(JsonArray events, String expectedUrl, String expectedMethod){
        return events.stream().map(o->(JsonObject)o)
                .filter(event-> event.getJsonObject("params").getJsonObject("request").getString("url")
                        .equals(expectedUrl) &&
                        event.getJsonObject("params").getJsonObject("request").getString("method").equals(expectedMethod)
                ).findFirst();
    }
    /**
     * Assumes WebVoyager event format.
     * @param events
     * @param expectedUrl
     * @param expectedMethod
     * @return true if one of the events in the provided json array is a network request matching the provided url.
     */
    private boolean urlMatches(JsonArray events, String expectedUrl, String expectedMethod){
        return getEventWhereUrlMatches(events, expectedUrl, expectedMethod).isPresent();
    }

    /**
     * Assumes WebVoyager event format.
     */
    private Optional<JsonObject> getEventWhereUrlMatches(JsonArray events, Pattern pattern, String expectedMethod){
        return events.stream().map(o->(JsonObject)o)
                .filter(event-> pattern.asMatchPredicate().test(event.getJsonObject("params").getJsonObject("request").getString("url")) &&
                        event.getJsonObject("params").getJsonObject("request").getString("method").equals(expectedMethod)
                ).findFirst();
    }

    private boolean urlMatches(JsonArray events, Pattern pattern, String expectedMethod){
        return getEventWhereUrlMatches(events, pattern, expectedMethod).isPresent();
    }

    private boolean requestPostDataExists(JsonObject event){
        return event.containsKey("params")?
                event.getJsonObject("params").containsKey("request")?
                        event.getJsonObject("params").getJsonObject("request").containsKey("postData")?
                                true:false:false:false;
    }

    private boolean requestPostDataContainsKeyWithValue(JsonObject event, String key, String value){
        String postData = event.getJsonObject("params").getJsonObject("request").getString("postData");
        String urlEncodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        String urlEncodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8);
        boolean matchFound = postData.contains(urlEncodedKey + "=" + urlEncodedValue);
        if(matchFound){
            log.info("Found {}={} ({}={}) in postData", key, value, urlEncodedKey, urlEncodedValue);
        }
        return matchFound;
    }

    private boolean requestPostDataContainsKeyWithValue(JsonArray events, Pattern pattern, String method, String key, String value){
        JsonObject event = getEventWhereUrlMatches(events, pattern, method ).get();
        return requestPostDataContainsKeyWithValue(event, key, value);
    }

    private boolean requestPostDataJsonContainsKeyWithValue(JsonObject event, String key, String value){
        JsonObject postData = new JsonObject(event.getJsonObject("params").getJsonObject("request").getString("postData"));

        if (postData.containsKey("assignment")){
            postData = postData.getJsonObject("assignment");
        }

        return postData.containsKey(key)?postData.getString(key).equals(value):false;
    }

    private boolean requestPostDataJsonContainsKeyWithValue(JsonArray events, String expectedUrl, String expectedMethod, String key, String value){
        JsonObject event = getEventWhereUrlMatches(events, expectedUrl, expectedMethod).get();
        return requestPostDataJsonContainsKeyWithValue(event, key, value);
    }


    private void computeEvaluationResults(RoutingContext rc)  {

        JsonObject request = rc.body().asJsonObject();

        String pathToResults = request.getString("results_path");
        JsonArray odoBotTasks = getTasks(request.getJsonArray("tasks"), Agent.ODO_BOT_NL);
        JsonObject courseIds = request.getJsonObject("course_ids");
        JsonObject assignmentIds = request.getJsonObject("assignment_ids");
        JsonObject quizIds = request.getJsonObject("quiz_ids");
        JsonObject pageSlugs = request.getJsonObject("page_slugs");
        JsonObject moduleIds = request.getJsonObject("module_ids");
        JsonArray modules = request.getJsonArray("modules");
        JsonArray pages = request.getJsonArray("pages");
        JsonArray quizzes = request.getJsonArray("quizzes");
        JsonArray assignments = request.getJsonArray("assignments");

        JsonObject results = new JsonObject();
        results.put("succeeded_tasks", new JsonArray());
        results.put("failed_tasks", new JsonArray());
        results.put("manifest", new JsonObject());

        try{


        File dir = new File(pathToResults);
        File[] contents = dir.listFiles();
        if(contents != null){
            for(File _log: contents){

                if(_log.getName().contains("navpath") || _log.getName().contains("task-query") || _log.isDirectory() || _log.getName().contains(".yaml") || !Agent.isValidAgent(_log.getName())){
                    //Skip navpath and task query logs
                    continue;
                }

                JsonObject taskInfo = getOdoBotTaskByFilename(_log.getName(), odoBotTasks);
                String evalId = taskInfo.getString("_evalId");
                JsonArray events = new JsonArray(new String(Files.readAllBytes(Path.of(_log.getPath()))));

                String taskDescription = taskInfo.getString("task");
                String targetCourseName = getEntryByTaskDescriptionContents(courseIds, taskDescription).getKey();

                JsonArray verificationDetails = new JsonArray();
                verificationDetails.add("task: " + taskDescription);
                verificationDetails.add("targetCourseName: " + targetCourseName);

                boolean success = switch (getTaskNumber(evalId)){
                    case 1 -> {
                        /**
                         * This is the create quiz task. We verify this one was done correctly by ensuring
                         * there exists an appropriate network event creating a quiz in the expected course.
                         */


                        Pattern saveQuizPattern = Pattern.compile("http:\\/\\/localhost:8088\\/courses\\/%s\\/quizzes\\/[0-9]+".formatted(courseIds.getString(targetCourseName)));
                        verificationDetails.add("saveQuizURLPattern: " + saveQuizPattern.pattern());

                        Optional<JsonObject> networkEvent = events.stream().map(o -> (JsonObject) o)
                                .filter(event -> event.getJsonObject("eventDetails").getString("name").equals("NETWORK_EVENT") &&
                                        event.getJsonObject("eventDetails").getString("method").equals("POST") &&
                                        saveQuizPattern.asMatchPredicate().test(event.getJsonObject("eventDetails").getString("url"))
                                ).findFirst();

                        yield networkEvent.isPresent();
                    }
                    case 2 -> {
                        /**
                         * This is the create a new assignment task. We verify this one by ensuring
                         * there exists a network event creating an assignment in the expected course.
                         */
                        String newAssignmentName = getItemByTaskDescriptionContents(assignments, taskDescription);

                        String expectedUrl = "http://localhost:8088/api/v1/courses/%s/assignments".formatted(
                                courseIds.getString(targetCourseName)
                        );
                        verificationDetails.add("create assignment url: " + expectedUrl);

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event -> event.getString("name").equals("NETWORK_EVENT") &&
                                        event.getString("method").equals("POST") &&
                                        event.getString("url")
                                                .equals(expectedUrl)

                                ).findFirst();

                        yield networkEvent.isPresent();

                    }
                    case 3 -> {
                        /**
                         * This is the create page task.
                         */


                        String expectedUrl = "http://localhost:8088/api/v1/courses/%s/pages".formatted(
                                courseIds.getString(targetCourseName)
                        );
                        verificationDetails.add("create page url: " + expectedUrl);

                        String newPageName = getItemByTaskDescriptionContents(pages, taskDescription);
                        verificationDetails.add("expected new page name: '" + newPageName + "'");

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT") &&
                                        event.getString("method").equals("POST") &&
                                        event.getString("url").equals(expectedUrl) &&
                                        new JsonObject(event.getString("responseBody")).getString("title").equals(newPageName)
                                ).findFirst();

                        yield networkEvent.isPresent();
                    }

                    case 4 ->{
                        /**
                         * Create a new module task
                         */
                        String newModuleName = getItemByTaskDescriptionContents(modules, taskDescription);
                        verificationDetails.add("expected new module name: '" + newModuleName + "'");

                        String expectedUrl = "http://localhost:8088/courses/%s/modules".formatted(
                                courseIds.getString(targetCourseName)
                        );
                        verificationDetails.add("create new module url: " + expectedUrl);

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT") &&
                                        event.getString("method").equals("POST") &&
                                        event.getString("url").equals(expectedUrl) &&
                                        new JsonObject(event.getString("requestBody")).getJsonObject("formData").getJsonArray("context_module[name]").getString(0).equals(newModuleName)
                                        )
                                .findFirst();

                        yield networkEvent.isPresent();
                    }

                    case 5 ->{

                        /**
                         * Edit quiz title
                         */


                        String targetQuizName = getEntryByTaskDescriptionContents(quizIds, taskDescription).getKey();
                        verificationDetails.add("target quiz name: '"+ targetQuizName + "'");

                        //TODO: hardcoded what edited quiz titles can be, at the very least this should be refactored into a shared constant between this and EvaluationTaskGenerationTask.java
                        String updatedQuizName = "modified - " + targetQuizName;
                        verificationDetails.add("expected updated quiz name: '" + updatedQuizName + "'");

                        String expectedUrl = "http://localhost:8088/courses/%s/quizzes/%s".formatted(
                                courseIds.getString(targetCourseName),
                                quizIds.getString(targetQuizName)
                        );
                        verificationDetails.add("edit quiz url: " + expectedUrl);

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT"))
                                .filter(event->event.getString("method").equals("POST"))
                                .filter(event->
                                        event.getString("url").equals(expectedUrl))
                                .filter(event->event.containsKey("responseBody")?
                                        new JsonObject(event.getString("responseBody")).getJsonObject("quiz").getString("title").equals(updatedQuizName):true
                                        ).findFirst();

                        yield networkEvent.isPresent();
                    }
                    case 6 ->{
                        /**
                         *  Edit a page title
                         */

                        String targetPageName = getEntryByTaskDescriptionContents(pageSlugs, taskDescription).getKey();
                        verificationDetails.add("targetPageName: '" + targetPageName + "'");

                        //TODO: hardcoded what edited titles can be, at the very least this should be refactored into a shared constant between this and EvaluationTaskGenerationTask.java
                        String updatedPageName = "modified - " + targetPageName;
                        verificationDetails.add("expected new page name: '" + updatedPageName + "'");

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT") &&
                                        event.getString("method").equals("PUT") &&
                                        event.getString("url").equals("http://localhost:8088/api/v1/courses/%s/pages/%s".formatted(
                                                courseIds.getString(targetCourseName),
                                                pageSlugs.getString(targetPageName)
                                        )) &&
                                        (event.containsKey("responseBody")?new JsonObject(event.getString("responseBody")).getString("title").equals(updatedPageName):true)

                                ).findFirst();

                        yield networkEvent.isPresent();
                    }
                    case 7 ->{

                        /**
                         *  Edit a module title
                         */

                        String targetModuleName = getEntryByTaskDescriptionContents(moduleIds, taskDescription).getKey();

                        verificationDetails.add("target module name: '" + targetModuleName + "'");

                        //TODO: hardcoded what edited names can be, at the very least this should be refactored into a shared constant between this and EvaluationTaskGenerationTask.java
                        String updatedName = "modified - " + targetModuleName;
                        verificationDetails.add("Expected updated module name: '" + updatedName+"'");

                        String expectedUrl = "http://localhost:8088/courses/%s/modules/%s".formatted(
                                courseIds.getString(targetCourseName),
                                moduleIds.getString(targetModuleName)
                        );
                        verificationDetails.add("edit module url: " + expectedUrl);

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT"))
                                .filter(event->event.getString("method").equals("POST"))
                                .filter(event->
                                        event.getString("url").equals(expectedUrl))
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

                        String targetAssignmentName = getEntryByTaskDescriptionContents(assignmentIds, taskDescription).getKey();
                        verificationDetails.add("target assignment name: '" + targetAssignmentName + "'");

                        String editedTitle = "modified - " + targetAssignmentName;
                        verificationDetails.add("expected new assignment name: '" + editedTitle + "'");

                        String expectedUrl = "http://localhost:8088/api/v1/courses/%s/assignments/%s".formatted(
                                courseIds.getString(targetCourseName),
                                assignmentIds.getString(targetAssignmentName)
                        );
                        verificationDetails.add("edit assignment url: " + expectedUrl);

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT"))
                                .filter(event->event.getString("method").equals("PUT"))
                                .filter(event->event.getString("url").equals(expectedUrl))
                                .filter(event->
                                        event.containsKey("responseBody")?new JsonObject(event.getString("responseBody")).getString("name").equals(editedTitle):true
                                ).findFirst();

                        yield networkEvent.isPresent();

                    }
                    case 9 ->{
                        /**
                         *   Delete an assignment
                         */

                        String targetAssignmentName = getEntryByTaskDescriptionContents(assignmentIds, taskDescription).getKey();
                        verificationDetails.add("target assignment to be deleted: '" + targetAssignmentName + "'");

                        String expectedUrl = "http://localhost:8088/api/v1/courses/%s/assignments/%s".formatted(
                                courseIds.getString(targetCourseName),
                                assignmentIds.getString(targetAssignmentName)
                        );
                        verificationDetails.add("delete assignment url: " + expectedUrl);

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT") &&
                                        event.getString("method").equals("DELETE") &&
                                        event.getString("url").equals(expectedUrl)).findFirst();

                        yield networkEvent.isPresent();
                    }
                    case 10 ->{
                        /**
                         * Delete a page
                         */

                        String targetPageName = getEntryByTaskDescriptionContents(pageSlugs, taskDescription).getKey();
                        verificationDetails.add("target page name: '" + targetPageName + "'");

                        String expectedUrl = "http://localhost:8088/api/v1/courses/%s/pages/%s".formatted(
                                courseIds.getString(targetCourseName),
                                pageSlugs.getString(targetPageName)
                        );
                        verificationDetails.add("delete page url: "+ expectedUrl);

                        Optional<JsonObject> networkEvent = events.stream().map(o->(JsonObject)o)
                                .map(event->event.getJsonObject("eventDetails"))
                                .filter(event->event.getString("name").equals("NETWORK_EVENT") &&
                                        event.getString("method").equals("DELETE") &&
                                        event.getString("url").equals(expectedUrl)).findFirst();

                        yield networkEvent.isPresent();

                    }
                    default ->{
                        throw new RuntimeException("Unknown task type!");
                    }
                };

                verificationDetails.add("verification result: " + success);
                //Add the verification result to the manifest
                results.getJsonObject("manifest").put(evalId, verificationDetails.size() > 0?verificationDetails:success);

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
