package ca.ualberta.odobot.explorer.canvas.resources;

import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Iterator;

public class Quiz extends BaseResource{

    private static final Logger log = LoggerFactory.getLogger(Quiz.class);

    private String name;

    private String body;

    private int id = -1;

    private String quizPageUrl;

    private String quizEditPageUrl;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getQuizEditPageUrl() {
        return quizEditPageUrl;
    }

    public void setQuizEditPageUrl(String quizEditPageUrl) {
        this.quizEditPageUrl = quizEditPageUrl;
        if(id == -1){
            this.id = parseIdFromUrl(this.quizEditPageUrl);
        }
    }

    public String getQuizPageUrl() {
        return quizPageUrl;
    }

    public void setQuizPageUrl(String quizPageUrl) {
        this.quizPageUrl = quizPageUrl;
        if(id == -1){
            this.id = parseIdFromUrl(this.quizPageUrl);
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }


    private int parseIdFromUrl(String urlString){
        try{
            URL url = new URL(urlString);
            String [] pathSegments = url.getPath().split("/");

            Iterator<String> it = Arrays.stream(pathSegments).iterator();
            while (it.hasNext()){
                String segment = it.next();
                if(segment.equals("quizzes")){
                    return Integer.parseInt(it.next());
                }
            }


        }catch (MalformedURLException e){
            log.error(e.getMessage(), e);
        }
        log.error("Error parsing quiz id from url! Returning id = -1");
        return -1;
    }

    @Override
    public JsonObject getRuntimeData() {
        JsonObject result = new JsonObject();

        if(getQuizPageUrl() != null){
            result.put("quizPageUrl", getQuizPageUrl());
        }

        if(getQuizEditPageUrl() != null){
            result.put("quizEditPageUrl", getQuizEditPageUrl());
        }

        if(id != -1){
            result.put("id", id);
        }

        return result;
    }

    @Override
    public void setRuntimeData(JsonObject data) {

        if(data.containsKey("quizPageUrl")){
            setQuizPageUrl(data.getString("quizPageUrl"));
        }

        if(data.containsKey("quizEditPageUrl")){
            setQuizEditPageUrl(data.getString("quizEditPageUrl"));
        }

        if(data.containsKey("id")){
            setId(data.getInteger("id"));
        }

    }
}
