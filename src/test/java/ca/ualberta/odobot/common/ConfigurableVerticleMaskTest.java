package ca.ualberta.odobot.common;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Secrets never reach the log when a service prints its configuration.
 */
class ConfigurableVerticleMaskTest {

    @Test
    void secretsAreMaskedAtAnyDepthAndTheConfigIsUntouched(){
        JsonObject config = new JsonObject()
                .put("openAI", new JsonObject().put("secretKey", "sk-proj-abc").put("model", "gpt-x"))
                .put("elasticsearch", new JsonObject().put("password", "pw").put("host", "es"))
                .put("api_key", "k").put("accessToken", "t").put("hosts", new JsonArray().add(new JsonObject().put("apiKey", "x")))
                .put("port", 8076);

        JsonObject masked = ConfigurableVerticle.masked(config);

        assertEquals("***", masked.getJsonObject("openAI").getString("secretKey"));
        assertEquals("gpt-x", masked.getJsonObject("openAI").getString("model"));
        assertEquals("***", masked.getJsonObject("elasticsearch").getString("password"));
        assertEquals("***", masked.getString("api_key"));
        assertEquals("***", masked.getString("accessToken"));
        assertEquals("***", masked.getJsonArray("hosts").getJsonObject(0).getString("apiKey"));
        assertEquals(8076, masked.getInteger("port"));
        assertEquals("sk-proj-abc", config.getJsonObject("openAI").getString("secretKey"), "the config itself is not changed");
    }
}
