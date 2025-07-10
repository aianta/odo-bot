package ca.ualberta.odobot.taskgenerator.canvas;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CanvasTask extends JsonObject {

    Pattern parameterPattern = Pattern.compile("(?<=\\[)[a-zA-Z0-9 ]+(?=\\])");

    public CanvasTask(){
        put("source", new JsonObject());
    }
    public CanvasTask setLocalPath(String localPath){
        getJsonObject("source").put("localPath", localPath);
        return this;
    }

    public String getLocalPath(){
        return getJsonObject("source").getString("localPath");
    }

    public UUID getId(){
        return UUID.fromString(getString("id"));
    }

    public CanvasTask setId(UUID id){
        put("id", id.toString());
        return this;
    }

    public String getPlainTask(){
        return getString("plainTask");
    }

    public CanvasTask setPlainTask(String plainTask){
        put("plainTask", plainTask);
        return this;
    }

    public CanvasTask setPlainTaskPrompt(String prompt){
        getJsonObject("source").put("plainTaskPrompt", prompt);
        return this;
    }

    public CanvasTask setParameterizedTaskPrompt(String prompt){
        getJsonObject("source").put("parameterizedTaskPrompt", prompt);
        return this;
    }

    public CanvasTask setParameterizedTask(String task){
        put("parameterizedTask", task);
        extractParameters(task);
        return this;
    }

    public void extractParameters(String task){
        put("parameters", new JsonArray());
        Matcher matcher = parameterPattern.matcher(task);
        while (matcher.find()){
            getJsonArray("parameters").add(matcher.group(0));
        }
    }

}
