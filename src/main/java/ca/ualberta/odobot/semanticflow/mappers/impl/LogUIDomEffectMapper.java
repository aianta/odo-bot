package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.DomEffect;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogUIDomEffectMapper extends JsonMapper<DomEffect> {

    private static final Logger log = LoggerFactory.getLogger(LogUIDomEffectMapper.class);


    public DomEffect map(JsonObject event){

        JsonObject eventDetails = event.getJsonObject("eventDetails");
        JsonObject domData = new JsonObject(eventDetails.getString("domSnapshot"));
        JsonObject node = firstNode(event);

        DomEffect result = new DomEffect();
        result.setDomSnapshot(Jsoup.parse(domData.getString("outerHTML")));
        result.setXpath(node.getString("xpath"));
        result.setEffectElement(extractElement(node.getString("outerHTML")));
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
            log.warn("Got a DOM ADD event with a broken xpath? This is really bad, investigate this please. Anyways, skipping for now...");
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
