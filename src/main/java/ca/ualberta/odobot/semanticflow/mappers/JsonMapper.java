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

        /**
         * Handle dangling table components special cases.
         * Note: Do not include closing angle bracket on opening tag in startsWith, to allow for html attributes.
         */
        if(html.startsWith("<tbody") && html.endsWith("</tbody>")){
            return handleTableFragment(html, "tbody");
        }

        if(html.startsWith("<thead") && html.endsWith("</thead>")){
            return handleTableFragment(html, "thead");
        }

        if(html.startsWith("<tr") && html.endsWith("</tr>")){
            return handleTableFragment(html, "tr");
        }

        if(html.startsWith("<th") && html.endsWith("</th>")){
            return handleTableFragment(html, "th");
        }

        if(html.startsWith("<td") && html.endsWith("</td>")){
            return handleTableFragment(html, "td");
        }

        Document doc = Jsoup.parseBodyFragment(html);
        if(doc.body().childrenSize() != 1){
            log.warn("More than one or no ({}) elements found when trying to extract HTML element from: {}", doc.body().childrenSize(), html);
            //log.warn("ParsedDocument: {}\n", doc.outerHtml());
            doc.body().children().forEach(child->log.warn("{}",child));
        }

        //log.debug("Extracted element from:\n {}", html);

        return doc.body().firstElementChild();
    }

    /**
     * JSoup doesn't care to parse <tbody></tbody>, not enclosed in <table></table> so handle that as a special case.
     * @return
     */
    private Element handleTableFragment(String html, String targetTag){
        String wrap = "<table>" + html + "</table>" ;
        Document doc = Jsoup.parseBodyFragment(wrap);
        Element target = doc.body().firstElementChild();
        while (!target.tagName().equals(targetTag) && target.firstElementChild() != null){
            target = target.firstElementChild();
        }

//        Element target = doc.body().firstElementChild().firstElementChild();
        if(!target.tagName().equals(targetTag)){
            log.warn("Couldn't handle {}", targetTag);
            return null;
        }
        return target;
    }

}
