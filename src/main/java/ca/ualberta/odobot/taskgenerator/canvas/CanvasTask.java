package ca.ualberta.odobot.taskgenerator.canvas;

import com.fasterxml.jackson.core.JsonParseException;
import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@DataObject
public class CanvasTask extends JsonObject {

    private static final Logger log = LoggerFactory.getLogger(CanvasTask.class);
    Pattern parameterPattern = Pattern.compile("(?<=\\[)[a-zA-Z0-9 ]+(?=\\])");

    public static CanvasTask fromJson(JsonObject input){
        CanvasTask result = new CanvasTask(input);
        return result;
    }

    public static CanvasTask fromRow(Row row, JsonObject parameters){
        if(parameters == null){
            throw new RuntimeException("parameters object cannot be null!");
        }

        CanvasTask result = new CanvasTask();
        result.setLocalPath(row.getString("local_path"));
        result.setId(row.getUUID("id"));
        result.setPlainTask(row.getString("plain_text"));
        //Note: do not use setParameterized task, as we do fancy logic there.
        result.put("parameterizedTask", row.getString("parameterized_text"));

        result.setParameterizedTaskPrompt(row.getString("parameterized_prompt"));
        result.setPlainTaskPrompt(row.getString("plain_prompt"));
        result.put("parameters", parameters);

        return result;
    }

    public CanvasTask(JsonObject input){
        this.put("source", input.getJsonObject("source"));
        this.setId(UUID.fromString(input.getString("id")));

        if(input.containsKey("plainTask")){
            this.setPlainTask(input.getString("plainTask"));
        }
        if(input.containsKey("parameterizedTask")){
            this.put("parameterizedTask",input.getString("parameterizedTask"));
        }

        if(input.containsKey("parameters")){
            this.put("parameters", input.getJsonObject("parameters"));
        }
    }

    public JsonObject toJson(){
        return this;
    }

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

    public String getParameterizedPrompt(){
        return getJsonObject("source").getString("parameterizedTaskPrompt");
    }

    public String getPlainPrompt(){
        return getJsonObject("source").getString("plainTaskPrompt");
    }

    public String getParameterizedTask(){
        return getString("parameterizedTask");
    }

    public JsonObject getParameters(){
        return getJsonObject("parameters");
    }

    public void refreshParameterizedTask(){
        setParameterizedTask(getParameters().encode());
    }
    public CanvasTask setParameterizedTask(String task){
        put("parameterizedTask", task);

        //try parsing the output as json.
        try{

            JsonObject parameters = new JsonObject(task);
            put("parameters", parameters);

            //If we succeed, generate a parameterized version of this task by replacing the parameter values with their types in the plain text task.
            Iterator<Map.Entry<String,Object>> it = parameters.iterator();
            String parameterizedTask = getPlainTask();
            while (it.hasNext()){
                Map.Entry<String,Object> parameter = it.next();
                String key = parameter.getKey();
                String value = (String)parameter.getValue();

                log.info("Searching for {}", key);
                Matcher matcher = Pattern.compile(key).matcher(parameterizedTask);
                while (matcher.find()){
                    MatchResult matchResult = matcher.toMatchResult();
                    log.info("Found parameter value: {} @ [{}-{}] ", matcher.group(0), matchResult.start(), matchResult.end());
                }


                 parameterizedTask = parameterizedTask.replaceAll(key, "[[" + value + "]]");

               }

            log.info("parameterized Task:\n{}", parameterizedTask);

            put("parameterizedTask", parameterizedTask);

        }catch (Exception e){
            log.error(e.getMessage(), e);
            log.error("Could not parse input as json!\n{}", task);
        }
        //extractParameters(task);
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
