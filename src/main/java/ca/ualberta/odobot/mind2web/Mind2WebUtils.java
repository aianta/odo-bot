package ca.ualberta.odobot.mind2web;


import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.semanticflow.Utils.*;


public class Mind2WebUtils {

    private static final Logger log = LoggerFactory.getLogger(Mind2WebUtils.class);

    public static Set<String> targetTags = new HashSet<>();


    private static class BuckeyeMismatch extends Exception{
        public String expectedAttributeXpath;

        public BuckeyeMismatch(String xpath){
            this.expectedAttributeXpath = xpath;
        }

    }

    private static class AlreadyDone{
        Future<Set<DynamicXPath>> future;
        String id;
        String website;

        String rawHtml;

        boolean isDone;

        public Future<Set<DynamicXPath>> getFuture(){
            return future;
        }
    }

    public static List<Future<Set<DynamicXPath>>> taskToDXpath(JsonObject task, List<String> xpathsFromModel){


        //Get the actions from the task
        JsonArray actions = task.getJsonArray("actions");
        String taskId = task.getString("annotation_id");
        String website = task.getString("website");

        //Go through all the actions in this task and check queue up requests to the database to check if they're already done.
         return actions.stream()
                .map(o->(JsonObject)o)
                 //TODO: comment
                .map(json->new String [] {json.getString("raw_html"), taskId + "|" +json.getString("action_uid")})
                 .map(data->{
                     AlreadyDone alreadyDone = new AlreadyDone();
                     alreadyDone.future = Mind2WebService.sqliteService.hasDynamicXpathEntry(data[1], website).compose(_isDone->{
                         if(_isDone){
                             log.info("{} is already done", alreadyDone.id);
                             return Future.succeededFuture(Set.of());
                         }else{
                             String cleanHTML = HTMLCleaningTools.clean(alreadyDone.rawHtml);
                             Document doc = Jsoup.parse(cleanHTML);

                             //Create a new mining task to execute through a thread pool, which uses the document and xpaths to mine for dynamic xpaths.
                             DynamicXpathMiningTask miningTask = new DynamicXpathMiningTask(cleanHTML, alreadyDone.website, doc, xpathsFromModel);
                             DynamicXpathMiner.executorService.submit(miningTask);

                             return miningTask.getFuture();
                         }
                     });
                     alreadyDone.id = data[1];
                     alreadyDone.website = website;
                     alreadyDone.rawHtml = data[0];

                     return alreadyDone;
                 })
                 .map(alreadyDone -> alreadyDone.getFuture())
                 .collect(Collectors.toList());

    }

    public static Trace taskToTrace(JsonObject task){

        Trace trace = new Trace();
        trace.setWebsite(task.getString("website"));
        trace.setDomain(task.getString("domain"));
        trace.setSubdomain(task.getString("subdomain"));
        trace.setAnnotationId(task.getString("annotation_id"));
        trace.setConfirmedTask(task.getString("confirmed_task"));
        trace.setActionRepresentation(task.getString("action_reprs"));

        //Get the actions from the task
        JsonArray actions = task.getJsonArray("actions");

        //And process them, adding them to the trace
        actions.stream()
                .map(o->(JsonObject)o)
                .map(json->processAction(json))
                .forEach(operation -> trace.add(operation));

        //Add start and end 'operations' to trace. These help in aligning all traces/paths for a particular website in the nav model.
        trace.add(0, new Start());
        trace.add(new End());

        return trace;
    }

    public static Operation processAction(JsonObject action){

        JsonObject opJson = action.getJsonObject("operation");



        String targetElementXPath = null;
        try{
            //resolveXPath(action.getString("raw_html").replaceAll("iframe", "div"), action.getString("action_uid"));
            action.put("raw_html", HTMLCleaningTools.clean(action.getString("raw_html")));
            //action.put("raw_html", action.getString("raw_html").replaceAll("iframe", "div"));

            targetElementXPath = resolveXPath(action.getString("raw_html"), action.getString("action_uid"));
        }catch (BuckeyeMismatch e){
            log.error("Buckeye Mismatch! Expected {}", e.expectedAttributeXpath);
            //log.error("Action: \n{}", action.put("raw_html", "<see state.html>").put("cleaned_html", "<removed>").encodePrettily());
            System.exit(0);
        }catch (IOException ioe){
            log.error(ioe.getMessage(), ioe);
        }

        Operation op = null;

        switch (opJson.getString("op")){
            case "CLICK":
                Click click = new Click();
                op = click;
                break;
            case "TYPE":
                Type type = new Type();
                type.setValue(opJson.getString("value"));
                op = type;
                break;
            case "SELECT":
                SelectOption selectOption = new SelectOption();
                selectOption.setValue(opJson.getString("value"));
                op = selectOption;
                break;
        }

        //Set the op's target element xpath;
        if(op != null){
            op.setActionId(action.getString("action_uid"));
            op.setRawHTML(action.getString("raw_html"));
            op.setTargetElementXpath(targetElementXPath);
        }

        return op;

    }


    private static String resolveXPath(String rawHTML, String actionId ) throws BuckeyeMismatch, IOException {

        Files.deleteIfExists(Path.of("state.html"));

        File htmlFile = new File("state.html");

        try(FileWriter fw = new FileWriter(htmlFile);
            BufferedWriter bw = new BufferedWriter(fw);
        ){
            bw.write(rawHTML);
            bw.flush();
            fw.flush();
        }catch (IOException e){
            log.error(e.getMessage(), e);
        }




        Document document = Jsoup.parse(rawHTML);
        String targetAttribute = "//*[@data_pw_testid_buckeye='%s']".formatted(actionId);
        //log.info("Identifying target element with buckeye attribute: {}", targetAttribute );

        Element targetElement = null;
        try{
            targetElement = document.selectXpath(targetAttribute).get(0);
            //log.info("Target Element was: {}", targetElement.tagName());
            targetTags.add(targetElement.tagName());
        }catch (IndexOutOfBoundsException e){
            throw new BuckeyeMismatch(targetAttribute);
        }


        String computedXpath = computeXpathNoRoot(targetElement);
        //log.info("Computed xpath: {}", computedXpath);



        Element verifiedElement = document.selectXpath(computedXpath).get(0);

        if(!targetElement.equals(verifiedElement)){
            log.error("Failed to resolve proper xpath!");
            throw new RuntimeException("Failed to resolve proper xpath!");
        }


        return computedXpath;

    }

}
