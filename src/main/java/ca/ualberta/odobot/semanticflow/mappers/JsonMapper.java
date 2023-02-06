package ca.ualberta.odobot.semanticflow.mappers;

import ca.ualberta.odobot.semanticflow.model.AbstractArtifact;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * Base mapper class. Mapper classes are used to decouple the structure of the JSON
 * data retrieved from elasticsearch from the rest of the application logic.
 *
 * @param <T> The type of artifact this mapper maps to, from an event JsonObject.
 */
public abstract class JsonMapper<T extends AbstractArtifact> {
    private static final String DOM_FIELD = "eventDetails_domSnapshot";

    /**
     * @param event the JsonObject containing a semantic artifact of a particular type.
     * @return the semantic artifact encoded in the JsonObject
     */
    public abstract T map(JsonObject event);

    protected Document getDOMSnapshot(JsonObject event){
        JsonObject domSnapshot = new JsonObject(event.getString(DOM_FIELD));
        return Jsoup.parse(domSnapshot.getString("outerHTML"));
    }


}
