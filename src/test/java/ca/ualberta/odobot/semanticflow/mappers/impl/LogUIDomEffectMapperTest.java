package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.model.DomEffect;
import ca.ualberta.odobot.semanticflow.model.Effect;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OdoX reports the changes of one DOM mutation callback as one DOM_EFFECT per action, with all of its nodes; each event
 * maps to one DomEffect whose primary node is the most relevant one.
 */
class LogUIDomEffectMapperTest {

    private static JsonObject node(String xpath, String html){
        return new JsonObject().put("xpath", xpath).put("outerHTML", html).put("localName", "div")
                .put("baseURI", "http://shop.test/admin/catalog/product/").put("outerText", html.replaceAll("<[^>]+>", ""));
    }

    private static JsonObject event(String action, JsonObject... nodes){
        JsonArray array = new JsonArray();
        for(JsonObject n: nodes) array.add(n);
        String snapshot = new JsonObject().put("outerHTML", "<html><body><div id='grid'><div class='row'>a</div></div></body></html>").encode();
        return new JsonObject().put("eventType", "customEvent").put("eventDetails", new JsonObject()
                .put("name", "DOM_EFFECT").put("action", action).put("nodes", array.encode()).put("domSnapshot", snapshot));
    }

    @Test
    void theTopMostLargestNodeIsPrimaryAndEveryNodeIsKept(){
        JsonObject event = event("add",
                node("/html/body/div[1]/div[1]", "<div class='row'>a</div>"),
                node("/html/body/section", "<section><span>x</span><span>y</span></section>"),
                node("/html/body/div[2]", "<div>b</div>"),
                node("/div", "<div>off-document template</div>"));

        DomEffect effect = new LogUIDomEffectMapper(false).map(event);

        assertEquals(DomEffect.EffectType.ADD, effect.getAction());
        assertEquals("/html/body/section", effect.getXpath(), "top-most, then the largest subtree");
        assertEquals(List.of("/html/body/section", "/html/body/div[2]", "/html/body/div[1]/div[1]"), effect.getXpaths(),
                "the detached addition is dropped, the rest ordered by relevance");
        assertTrue(effect.getEffectElements().isEmpty(), "live runs don't parse elements");
        assertNull(effect.getDomSnapshot());
    }

    @Test
    void parsingMapsEveryNodeOnceAndEffectSeesThemAll(){
        JsonObject event = event("add", node("/html/body/p[1]", "<p>one</p>"), node("/html/body/p[2]", "<p>two</p>"));

        DomEffect effect = new LogUIDomEffectMapper(true).map(event);
        List<String> texts = effect.getEffectElements().stream().map(Element::text).toList();

        assertEquals(List.of("one", "two"), texts);
        assertEquals("one", effect.getEffectElement().text(), "the primary element");
        assertNotNull(effect.getDomSnapshot());

        Effect timelineEffect = new Effect();
        timelineEffect.add(effect);
        assertEquals(2, timelineEffect.madeVisible().size(), "the Effect's element sets cover every node of the change");
    }

    @Test
    void removalsAreRankedBySizeSinceTheirXpathsAreDetached(){
        DomEffect effect = new LogUIDomEffectMapper(false).map(event("remove",
                node("/li", "<li>a</li>"), node("/ul", "<ul><li>b</li><li>c</li></ul>")));

        assertEquals("/ul", effect.getXpath());
        assertEquals(DomEffect.EffectType.REMOVE, effect.getAction());
    }

    @Test
    void anEventWithOnlyDetachedAdditionsMapsToNothing(){
        assertNull(new LogUIDomEffectMapper(false).map(event("add", node("/div", "<div>t</div>"))));
    }

    @Test
    void aSingleNodeEventKeepsTheSingleNodeShape(){
        DomEffect effect = new LogUIDomEffectMapper(false).map(event("hide", node("/html/body/div[2]", "<div>box</div>")));

        assertEquals("/html/body/div[2]", effect.getXpath());
        assertEquals(List.of("/html/body/div[2]"), effect.getXpaths());
        assertEquals(DomEffect.EffectType.HIDE, effect.getAction());
    }
}
