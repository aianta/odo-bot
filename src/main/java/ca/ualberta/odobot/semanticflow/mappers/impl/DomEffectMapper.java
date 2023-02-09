package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.DomEffect;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link DomEffectMapper} contains all the logic necessary to produce a {@link DomEffect} object
 * from a json object with the correct information.
 */
public class DomEffectMapper extends JsonMapper<DomEffect> {
    private static final Logger log = LoggerFactory.getLogger(DomEffectMapper.class);

    private static final String ACTION_FIELD = "eventDetails_action";
    private static final String ADD_ACTION = "add";
    private static final String SHOW_ACTION = "show";
    private static final String HIDE_ACTION = "hide";
    private static final String REMOVE_ACTION = "remove";

    private static final String NODES_FIELD = "eventDetails_nodes";
    private static final String XPATH_FIELD = "eventDetails_xpath";

    private static final String NODE_HTML_FIELD = "outerHTML";
    private static final String NODE_TAG_FIELD = "localName";
    private static final String NODE_ID_FIELD = "id";
    private static final String NODE_TEXT_FIELD = "outerText";
    private static final String NODE_BASEURI_FIELD = "baseURI";

    @Override
    public DomEffect map(JsonObject event) {

        //Get the dom node/element that was added/shown/hidden/removed
        JsonObject node = firstNode(event);

        DomEffect result = new DomEffect();
        result.setDomSnapshot(getDOMSnapshot(event));
        result.setXpath(event.getString(XPATH_FIELD));
        result.setEffectElement(extractElement(node.getString(NODE_HTML_FIELD)));
        result.setTag(node.getString(NODE_TAG_FIELD));
        result.setHtmlId(node.getString(NODE_ID_FIELD));
        result.setText(node.getString(NODE_TEXT_FIELD));
        result.setBaseURI(node.getString(NODE_BASEURI_FIELD));


        switch (event.getString(ACTION_FIELD)){
            case ADD_ACTION -> result.setAction(DomEffect.EffectType.ADD);
            case SHOW_ACTION -> result.setAction(DomEffect.EffectType.SHOW);
            case HIDE_ACTION -> result.setAction(DomEffect.EffectType.HIDE);
            case REMOVE_ACTION -> result.setAction(DomEffect.EffectType.REMOVE);
        }

        return result;
    }


    /**
     * Returns the first, and often, only element in the nodes array.
     * @param event
     * @return
     */
    private JsonObject firstNode(JsonObject event){
        JsonArray nodes = new JsonArray(event.getString(NODES_FIELD));
        return nodes.getJsonObject(0);
    }
}
