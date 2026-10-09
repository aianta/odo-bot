package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.model.DomEffect;
import ca.ualberta.odobot.semanticflow.model.Effect;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogUIDomEffectMapperTest {

    private static JsonObject domEffectEvent(String action) {
        JsonObject node = new JsonObject()
                .put("xpath", "/html/body/div[2]")
                .put("outerHTML", "<div id=\"grid\"><span>Teton</span></div>")
                .put("localName", "div")
                .put("id", "grid")
                .put("outerText", "Teton")
                .put("baseURI", "http://172.23.0.2/admin/catalog/product/");
        return new JsonObject()
                .put("eventType", "customEvent")
                .put("eventDetails", new JsonObject()
                        .put("name", "DOM_EFFECT")
                        .put("action", action)
                        .put("domSnapshot", new JsonObject().put("outerHTML", "<html><body><div></div><div id=\"grid\"><span>Teton</span></div></body></html>").encode())
                        .put("nodes", new JsonArray().add(node).encode()));
    }

    @Test
    void withoutParsingKeepsWhatLiveRunsUse() {
        DomEffect effect = new LogUIDomEffectMapper(false).map(domEffectEvent("hide"));

        assertEquals(DomEffect.EffectType.HIDE, effect.getAction());
        assertEquals("/html/body/div[2]", effect.getXpath());
        assertEquals("div", effect.getTag());
        assertEquals("grid", effect.getHtmlId());
        assertEquals("Teton", effect.getText());
        assertEquals("http://172.23.0.2/admin/catalog/product/", effect.getBaseURI().toString());

        assertNull(effect.getDomSnapshot());
        assertNull(effect.getEffectElement());
        assertNull(effect.getTargetElement());
    }

    @Test
    void parsesByDefault() {
        DomEffect effect = new LogUIDomEffectMapper().map(domEffectEvent("add"));

        assertNotNull(effect.getDomSnapshot());
        assertEquals("grid", effect.getEffectElement().id());
        assertEquals("Teton", effect.getTargetElement().text());
    }

    @Test
    void effectsWithoutParsedElementsStillSerialize() {
        Effect effect = new Effect();
        effect.add(new LogUIDomEffectMapper(false).map(domEffectEvent("add")));

        assertEquals("E", effect.symbol());
        assertEquals(1, effect.getBaseURIs().size());
        assertTrue(effect.toJson().getJsonArray("netVisible").isEmpty());
    }
}
