package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.DomEffect;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * {@link DomEffectMapper} contains all the logic necessary to produce a {@link DomEffect} object
 * from a json object with the correct information.
 */
public class DomEffectMapper extends JsonMapper<DomEffect> {
    private static final Logger log = LoggerFactory.getLogger(DomEffectMapper.class);

    private static final String ACTION_FIELD = "eventDetails_action";

    private static final String NODES_FIELD = "eventDetails_nodes";

    private static final String NODE_HTML_FIELD = "outerHTML";
    private static final String NODE_XPATH_FIELD = "xpath";

    /**
     * Map a DOM effect event to one {@link DomEffect} covering all of its nodes, most relevant first, see
     * {@link LogUIDomEffectMapper#relevantNodes}. The page snapshot is parsed once for the whole change.
     *
     * @return the DomEffect, or null if none of the event's nodes is worth keeping.
     */
    @Override
    public DomEffect map(JsonObject event) {
        DomEffect.EffectType action = LogUIDomEffectMapper.action(event.getString(ACTION_FIELD));
        List<JsonObject> nodes = LogUIDomEffectMapper.relevantNodes(new JsonArray(event.getString(NODES_FIELD)), action);
        if(nodes.isEmpty()){
            log.warn("Got a DOM {} event with no locatable node, skipping it.", action);
            return null;
        }

        DomEffect result = LogUIDomEffectMapper.fromPrimaryNode(nodes.get(0), action);
        result.setDomSnapshot(getDOMSnapshot(event));
        List<Element> elements = nodes.stream().map(node->extractElement(node.getString(NODE_HTML_FIELD))).toList();
        result.setEffectElement(elements.get(0));
        if(nodes.size() > 1){
            result.setXpaths(nodes.stream().map(node->node.getString(NODE_XPATH_FIELD)).toList());
            result.setEffectElements(elements);
        }
        return result;
    }

}
