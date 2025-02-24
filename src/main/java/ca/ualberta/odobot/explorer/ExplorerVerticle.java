package ca.ualberta.odobot.explorer;

import ca.ualberta.odobot.common.HttpServiceVerticle;
import io.reactivex.rxjava3.core.Completable;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ListIterator;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author Alexandru Ianta
 * A wrapper verticle for the Odo Explorer, a selenium based tool which
 * explores a web application while running the Odo Sight extension in
 * order to generate data that can be used to train TPG.
 */
public class ExplorerVerticle extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(ExplorerVerticle.class);

    private static final String HOST = "0.0.0.0";

    private static final int PORT = 8076;

    private static final String API_PATH_PREFIX = "/api/*";

    public String serviceName(){return "Data Generation (Explorer) Service";}

    public String configFilePath(){
        return "config/explorer.yaml";
    }


    public Completable onStart(){
        super.onStart();
        try{

            api.route(HttpMethod.POST, "/explore").handler(this::exploreValidationHandler);
            api.route(HttpMethod.POST, "/explore").handler(this::exploreHandler);

            api.route(HttpMethod.POST, "/plan").handler(this::planValidationHandler);
            api.route(HttpMethod.POST, "/plan").handler(this::planHandler);

            api.route(HttpMethod.POST, "/evaluationTasks").handler(this::evaluationTaskValidationHandler);
            api.route(HttpMethod.POST, "/evaluationTasks").handler(this::evaluationTasksHandler);

            api.route(HttpMethod.POST, "/evaluate").handler(this::evaluateValidationHandler);
            api.route(HttpMethod.POST, "/evaluate").handler(this::evaluateHandler);

            api.route(HttpMethod.POST, "/evaluationResults").handler(this::computeEvaluationResults);


        }catch (Exception e){
            log.error(e.getMessage(), e);
        }

        return Completable.complete();

    }

    private void computeEvaluationResults(RoutingContext rc)  {

        JsonObject request = rc.body().asJsonObject();

        String pathToResults = request.getString("results_path");
        JsonArray odoBotTasks = getOdoBotTasks(request.getJsonArray("tasks"));
        JsonObject courseIds = request.getJsonObject("course_ids");
        JsonObject assignmentIds = request.getJsonObject("assignment_ids");
        JsonObject quizIds = request.getJsonObject("quiz_ids");
        JsonObject pageSlugs = request.getJsonObject("page_slugs");
        JsonObject moduleIds = request.getJsonObject("module_ids");

        JsonObject results = new JsonObject();
        results.put("succedded_tasks", new JsonArray());
        results.put("failed_tasks", new JsonArray());
        results.put("manifest", new JsonObject());

        try{


        File dir = new File(pathToResults);
        File[] contents = dir.listFiles();
        if(contents != null){
            for(File _log: contents){

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
                                                )) &&
                                        new JsonObject(event.getString("responseBody")).getString("name").equals(newAssignmentName)

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
                                .filter(event->event.getString("name").equals("NETWORK_EVENT") &&
                                        event.getString("method").equals("POST") &&
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
                                .filter(event->event.getString("name").equals("NETWORK_EVENT") &&
                                        event.getString("method").equals("POST") &&
                                        event.getString("url").equals("http://localhost:8088/courses/%s/modules/%s".formatted(
                                                courseIds.getString(targetCourseName),
                                                moduleIds.getString(targetModuleName)
                                        )) &&
                                        //Verify that the correct name was entered
                                        new JsonObject(event.getString("responseBody"))
                                        .getJsonObject("context_module")
                                        .getString("name").equals(updatedName)


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
                                .filter(event->event.getString("name").equals("NETWORK_EVENT") &&
                                        event.getString("method").equals("PUT") &&
                                        event.getString("url").equals("http://localhost:8088/api/v1/courses/%s/assignments/%s".formatted(
                                                courseIds.getString(targetCourseName),
                                                assignmentIds.getString(targetAssignmentName)
                                        )) &&
                                        //If we have a response body, it should contain the expected name.
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
                    results.getJsonArray("succedded_tasks").add(evalId);
                }else{
                    results.getJsonArray("failed_tasks").add(evalId);
                }
            }
        }

        results.put("succedded", results.getJsonArray("succedded_tasks").size());
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

    private JsonObject getOdoBotTaskByFilename(String filename, JsonArray tasks){
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

        JsonArray tasks = getOdoBotTasks(config.getJsonArray("tasks"));


        Future f = null;

        ListIterator<JsonObject> taskIterator = tasks.stream().map(o->(JsonObject)o).collect(Collectors.toList()).listIterator();
        while (taskIterator.hasNext()){
            JsonObject _task = taskIterator.next();

            if(f == null){
                Promise<Void> promise = Promise.promise();
                promise.future()
                        .onFailure(err->serverError(rc, err));
                f = promise.future();

                EvaluateTask evaluateTask = new EvaluateTask(config, _task, promise);
                Thread thread = new Thread(evaluateTask);
                thread.start();
            }else{
                JsonObject finalConfig = config;
                f = f.compose(fDone->{

                    Promise<Void> promise = Promise.promise();
                    promise.future()
                            .onFailure(err->serverError(rc, err));

                    EvaluateTask evaluateTask = new EvaluateTask(finalConfig, _task, promise);
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
     * @return
     */
    private JsonArray getOdoBotTasks(JsonArray tasks){
        JsonArray result = tasks.stream()
                .map(o->(JsonObject)o)
                .map(task->task.getJsonObject("odoBot"))
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
