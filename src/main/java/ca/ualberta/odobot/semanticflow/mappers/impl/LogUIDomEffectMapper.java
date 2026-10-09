package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.DomEffect;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a LogUI DOM effect event to a {@link DomEffect}.
 *
 * <p>By default the page snapshot and the affected node are parsed with Jsoup and kept on the {@link DomEffect}. A
 * snapshot is a whole page, so a run that produces many DOM effects can hold gigabytes of parsed pages. Live runs do
 * not read them (they only use the effect's type, xpath and base URI), so the online event processor maps without
 * parsing.</p>
 */
public class LogUIDomEffectMapper extends JsonMapper<DomEffect> {

    private static final Logger log = LoggerFactory.getLogger(LogUIDomEffectMapper.class);

    private final boolean parseDom;

    public LogUIDomEffectMapper(){
        this(true);
    }

    /**
     * @param parseDom whether to parse the page snapshot and the affected node. Without parsing,
     *                 {@link DomEffect#getDomSnapshot()}, {@link DomEffect#getEffectElement()} and
     *                 {@link DomEffect#getTargetElement()} are null.
     */
    public LogUIDomEffectMapper(boolean parseDom){
        this.parseDom = parseDom;
    }


    public DomEffect map(JsonObject event){

        JsonObject eventDetails = event.getJsonObject("eventDetails");
        JsonObject node = firstNode(event);

        DomEffect result = new DomEffect();
        if(parseDom){
            JsonObject domData = new JsonObject(eventDetails.getString("domSnapshot"));
            result.setDomSnapshot(Jsoup.parse(domData.getString("outerHTML")));
            result.setEffectElement(extractElement(node.getString("outerHTML")));
        }
        result.setXpath(node.getString("xpath"));
        result.setTag(node.getString("localName"));
        result.setHtmlId(node.getString("id"));
        result.setText(node.getString("outerText"));
        result.setBaseURI(node.getString("baseURI"));

        switch (eventDetails.getString("action")){
            case "add" -> result.setAction(DomEffect.EffectType.ADD);
            case "remove"->result.setAction(DomEffect.EffectType.REMOVE);
            case "show"->result.setAction(DomEffect.EffectType.SHOW);
            case "hide"->result.setAction(DomEffect.EffectType.HIDE);

        }

        if(result.getAction() == DomEffect.EffectType.ADD && !result.getXpath().startsWith("/html")){
            //log.warn("Got a DOM ADD event with a broken xpath? This is really bad, investigate this please. Anyways, skipping for now...");
            return null;
        }

        return result;
    }

    /**
     * Returns the first, and often, only element in the nodes array.
     * @param event
     * @return
     */
    private JsonObject firstNode(JsonObject event){
        return new JsonArray(event.getJsonObject("eventDetails").getString("nodes")).getJsonObject(0);
    }

}
