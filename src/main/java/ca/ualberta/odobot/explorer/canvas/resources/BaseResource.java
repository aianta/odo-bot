package ca.ualberta.odobot.explorer.canvas.resources;

import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;

public abstract class BaseResource {
    private static final Logger log = LoggerFactory.getLogger(BaseResource.class);

    protected static Random random = new Random();
    protected String identifier;
    protected String identifierRef;

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public String getIdentifierRef() {
        return identifierRef;
    }

    public void setIdentifierRef(String identifierRef) {
        this.identifierRef = identifierRef;
    }

    public String makeEdit(String body){
        //Randomly pick some sub-section of the body
        String result = body.substring(random.nextInt(0, body.length()));
        if(result.length() < body.length()/2){ // If the randomly selected subsection is greater than half the original content.
            result += " " +  body; //Add the subsection and body together.
        }
        return result;
    }

    public abstract JsonObject getRuntimeData();

    public abstract void setRuntimeData(JsonObject data);

    protected int parseIdFromUrl(String urlString, String urlPredecessor){
        try{
            URL url = new URL(urlString);
            String [] pathSegments = url.getPath().split("/");

            Iterator<String> it = Arrays.stream(pathSegments).iterator();
            while (it.hasNext()){
                String segment = it.next();
                if(segment.equals(urlPredecessor)){
                    return Integer.parseInt(it.next());
                }
            }


        }catch (MalformedURLException e){
            log.error(e.getMessage(), e);
        }
        log.error("Error parsing quiz id from url! Returning id = -1");
        return -1;
    }

    JsonObject toJson(){
        JsonObject result = new JsonObject();

        if(getIdentifier() != null){
            result.put("identifier", getIdentifier());
        }

        if(getIdentifierRef() != null){
            result.put("identifierRef", getIdentifierRef());
        }

        if(getRuntimeData() != null){
            result.mergeIn(getRuntimeData());
        }


        return result;
    }
}
