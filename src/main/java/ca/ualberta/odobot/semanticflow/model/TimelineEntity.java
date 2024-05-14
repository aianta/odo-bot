package ca.ualberta.odobot.semanticflow.model;


import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static ca.ualberta.odobot.semanticflow.Utils.getNormalizedPath;

public interface TimelineEntity {
    static Logger log = LoggerFactory.getLogger(TimelineEntity.class);

    int size();

    String symbol();

    JsonObject toJson();

    long timestamp();

    JsonObject getSemanticArtifacts();

    static String getLocationPath(TimelineEntity e){

        if(e instanceof ClickEvent){
            ClickEvent entity = (ClickEvent) e;
            return getNormalizedPath(entity.getBaseURI());
        }

        if(e instanceof NetworkEvent){
            NetworkEvent entity = (NetworkEvent) e;
            return getNormalizedPath(entity.getRequestHeader("Referer"));
        }

        if(e instanceof DataEntry){
            DataEntry entity = (DataEntry) e;
            return getNormalizedPath(entity.lastChange().getBaseURI());
        }

        if(e instanceof Effect){
            Effect entity = (Effect) e;
            return getNormalizedPath(entity.getBaseURIs().iterator().next());
        }

        if(e instanceof ApplicationLocationChange){
            log.warn("ApplicationLocationChange entities do not have a single corresponding location! Returning null");
            return null;
        }

        throw new RuntimeException("Unrecognized Timeline Entity Class: %s".formatted(e.getClass().getName()));

    }

}
