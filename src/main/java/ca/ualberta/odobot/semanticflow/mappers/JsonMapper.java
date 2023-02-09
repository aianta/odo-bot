package ca.ualberta.odobot.semanticflow.mappers;

import ca.ualberta.odobot.semanticflow.model.AbstractArtifact;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base mapper class. Mapper classes are used to decouple the structure of the JSON
 * data retrieved from elasticsearch from the rest of the application logic.
 *
 * @param <T> The type of artifact this mapper maps to, from an event JsonObject.
 */
public abstract class JsonMapper<T extends AbstractArtifact> {
    private static final Logger log = LoggerFactory.getLogger(JsonMapper.class);

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

    protected Element extractElement(String html){

        if(html.startsWith("<tbody>") && html.endsWith("</tbody>")){
            return handleTbodyFragment(html);
        }

        Document doc = Jsoup.parseBodyFragment(html);
        if(doc.body().childrenSize() != 1){
            log.warn("More than one or no ({}) elements found when trying to extract HTML element from: {}", doc.body().childrenSize(), html);
            log.warn("ParsedDocument: {}\n", doc.outerHtml());
            doc.body().children().forEach(child->log.warn("{}",child));
        }
        return doc.body().firstElementChild();
    }

    /**
     * JSoup doesn't care to parse <tbody></tbody>, not enclosed in <table></table> so handle that as a special case.
     * @return
     */
    private Element handleTbodyFragment(String html){
        String wrap = "<table>" + html + "</table>" ;
        Document doc = Jsoup.parseBodyFragment(wrap);
        Element tbody = doc.body().firstElementChild().firstElementChild();
        if(!tbody.tagName().equals("tbody")){
            log.warn("Couldn't handle TBODY");
            return null;
        }
        return tbody;
    }

}
