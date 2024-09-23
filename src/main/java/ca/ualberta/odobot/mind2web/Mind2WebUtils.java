package ca.ualberta.odobot.mind2web;


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

import static ca.ualberta.odobot.semanticflow.Utils.*;


public class Mind2WebUtils {

    private static final Logger log = LoggerFactory.getLogger(Mind2WebUtils.class);


    public static Trace processTask(JsonObject task){

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


        return trace;
    }

    public static Operation processAction(JsonObject action){

        JsonObject opJson = action.getJsonObject("operation");


        String targetElementXPath = resolveXPath(action.getString("raw_html"), action.getString("action_uid"));

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


    private static String resolveXPath(String rawHTML, String actionId ){

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
        log.info("Identifying target element with buckeye attribute: {}", targetAttribute );
        Element targetElement = document.selectXpath(targetAttribute).get(0);

        String computedXpath = computeXpathNoRoot(targetElement);
        log.info("Computed xpath: {}", computedXpath);



        Element verifiedElement = document.selectXpath(computedXpath).get(0);

        if(!targetElement.equals(verifiedElement)){
            log.error("Failed to resolve proper xpath!");
            throw new RuntimeException("Failed to resolve proper xpath!");
        }


        return computedXpath;

    }

}