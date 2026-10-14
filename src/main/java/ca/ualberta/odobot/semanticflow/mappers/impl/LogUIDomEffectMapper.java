package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.DomEffect;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
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


    /**
     * Map a DOM effect event to one {@link DomEffect} covering all of its nodes. OdoX reports the changes of one DOM
     * mutation callback as one event per action, so an event can carry many nodes.
     *
     * @return the DomEffect, or null if none of the event's nodes is worth keeping.
     */
    public DomEffect map(JsonObject event){
        JsonObject eventDetails = event.getJsonObject("eventDetails");
        DomEffect.EffectType action = action(eventDetails.getString("action"));
        List<JsonObject> nodes = relevantNodes(new JsonArray(eventDetails.getString("nodes")), action);
        if(nodes.isEmpty()){
            return null;
        }

        DomEffect result = fromPrimaryNode(nodes.get(0), action);
        if(nodes.size() > 1){
            result.setXpaths(nodes.stream().map(node->node.getString("xpath")).toList());
        }
        if(parseDom){
            //One parse of the page snapshot for the whole change.
            JsonObject domData = new JsonObject(eventDetails.getString("domSnapshot"));
            result.setDomSnapshot(Jsoup.parse(domData.getString("outerHTML")));
            List<Element> elements = nodes.stream().map(node->extractElement(node.getString("outerHTML"))).toList();
            result.setEffectElement(elements.get(0));
            if(elements.size() > 1){
                result.setEffectElements(elements);
            }
        }
        return result;
    }

    /**
     * The DomEffect's single-node fields, from its primary node.
     */
    static DomEffect fromPrimaryNode(JsonObject node, DomEffect.EffectType action){
        DomEffect result = new DomEffect();
        result.setXpath(node.getString("xpath"));
        result.setTag(node.getString("localName"));
        result.setHtmlId(node.getString("id"));
        result.setText(node.getString("outerText"));
        result.setBaseURI(node.getString("baseURI"));
        result.setAction(action);
        return result;
    }

    /**
     * The nodes of a DOM effect event worth keeping, most relevant first.
     *
     * <p>Kept: nodes with an xpath; for additions, only nodes rooted at /html (nodes built off-document can't be located
     * in the page).</p>
     *
     * <p>Most relevant first: the top-most node (fewest xpath steps, i.e. the largest ancestor), then the node with the
     * largest HTML (the biggest subtree), then the order OdoX listed them in. Removed nodes are detached, so their xpaths
     * are relative to the removed subtree; for those the size decides.</p>
     */
    static List<JsonObject> relevantNodes(JsonArray nodes, DomEffect.EffectType action){
        List<JsonObject> kept = new ArrayList<>(nodes.size());
        for(int i = 0; i < nodes.size(); i++){
            JsonObject node = nodes.getJsonObject(i);
            String xpath = node.getString("xpath");
            if(xpath == null || (action == DomEffect.EffectType.ADD && !xpath.startsWith("/html"))){
                continue;
            }
            kept.add(node);
        }
        //List.sort is stable, so equally relevant nodes keep OdoX's order.
        kept.sort(Comparator.<JsonObject>comparingLong(node->xpathDepth(node.getString("xpath")))
                .thenComparing(Comparator.<JsonObject>comparingInt(node->node.getString("outerHTML", "").length()).reversed()));
        return kept;
    }

    private static long xpathDepth(String xpath){
        return xpath.chars().filter(c->c == '/').count();
    }

    static DomEffect.EffectType action(String action){
        return switch (action){
            case "add" -> DomEffect.EffectType.ADD;
            case "remove" -> DomEffect.EffectType.REMOVE;
            case "show" -> DomEffect.EffectType.SHOW;
            case "hide" -> DomEffect.EffectType.HIDE;
            default -> null;
        };
    }

}
