package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;

public class ApplicationLocationChange extends AbstractArtifact implements TimelineEntity{

    private static final Logger log = LoggerFactory.getLogger(ApplicationLocationChange.class);

    private URL from;
    private URL to;

    public String getFromPath(){
        return from.getPath().replaceAll("[0-9]+", "*").replaceAll("(?<=pages\\/)[\\s\\S]+", "*");
    }

    public String getToPath(){
        return to.getPath().replaceAll("[0-9]+", "*").replaceAll("(?<=pages\\/)[\\s\\S]+", "*");
    }

    public URL getFrom() {
        return from;
    }

    public void setFrom(String fromUrl){
        try{
            setFrom(new URL(fromUrl));
        }catch (MalformedURLException e){
            log.error(e.getMessage(), e);
        }
    }

    public void setFrom(URL from) {
        this.from = from;
    }

    public URL getTo() {
        return to;
    }

    public void setTo(String toUrl){
        try{
            setTo(new URL(toUrl));
        }catch (MalformedURLException e){
            log.error(e.getMessage(), e);
        }
    }

    public void setTo(URL to) {
        this.to = to;
    }

    @Override
    public int size() {
        return 1;
    }

    @Override
    public String symbol() {
        return "ALC";
    }

    @Override
    public JsonObject toJson() {
        return new JsonObject()
                .put("from", from.toString())
                .put("to", to.toString())
                .put("timestamp", getTimestamp().toString());
    }

    @Override
    public long timestamp() {
        return 0;
    }

    @Override
    public JsonObject getSemanticArtifacts() {
        return new JsonObject();
    }
}
