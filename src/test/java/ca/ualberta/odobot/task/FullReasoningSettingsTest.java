package ca.ualberta.odobot.task;

import ca.ualberta.odobot.common.LlmClientConfig;
import ca.ualberta.odobot.guidance.ExecutionMode;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code requireFullReasoning} is set per task; it is only accepted when the request's LLM settings can deliver the
 * model's full reasoning.
 */
class FullReasoningSettingsTest {

    private static JsonObject openRouter(JsonObject provider) {
        return new JsonObject().put("llm", new JsonObject().put("model", "qwen/qwen3.8-27b")
                .put("base_url", "https://openrouter.ai/api/v1")
                .put("extra_body", new JsonObject().put("provider", provider)));
    }

    private static JsonObject pinned() {
        return new JsonObject().put("only", new JsonArray().add("DeepInfra")).put("allow_fallbacks", false);
    }

    private static JsonObject task(Object requireFullReasoning) {
        return new JsonObject().put("id", "t").put("requireFullReasoning", requireFullReasoning);
    }

    @Test
    void isFalseUnlessTheTaskAsksForIt() {
        assertFalse(TaskSettings.requireFullReasoning(new JsonObject(), ExecutionMode.CHARTED, new JsonObject()));
        assertFalse(TaskSettings.requireFullReasoning(task(false), ExecutionMode.UNCHARTED, new JsonObject()));
    }

    @Test
    void isAcceptedWithPinnedOpenRouterProvidersOrALocalServer() {
        assertTrue(TaskSettings.requireFullReasoning(task(true), ExecutionMode.UNCHARTED, openRouter(pinned())));
        assertTrue(TaskSettings.requireFullReasoning(task(true), ExecutionMode.UNCHARTED, new JsonObject()),
                "a local server returns the reasoning it produces");
    }

    @Test
    void isRejectedWhenItCantBeMet() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskSettings.requireFullReasoning(task("yes"), ExecutionMode.UNCHARTED, new JsonObject()));
        assertThrows(IllegalArgumentException.class,
                () -> TaskSettings.requireFullReasoning(task(true), ExecutionMode.CHARTED, openRouter(pinned())));

        IllegalArgumentException unpinned = assertThrows(IllegalArgumentException.class,
                () -> TaskSettings.requireFullReasoning(task(true), ExecutionMode.UNCHARTED, openRouter(new JsonObject())));
        assertTrue(unpinned.getMessage().contains("provider.only"), unpinned.getMessage());

        IllegalArgumentException fallbacks = assertThrows(IllegalArgumentException.class,
                () -> TaskSettings.requireFullReasoning(task(true), ExecutionMode.UNCHARTED,
                        openRouter(pinned().put("allow_fallbacks", true))));
        assertTrue(fallbacks.getMessage().contains("allow_fallbacks"), fallbacks.getMessage());

        JsonObject excluded = openRouter(pinned());
        excluded.getJsonObject("llm").getJsonObject("extra_body").put("reasoning", new JsonObject().put("exclude", true));
        assertThrows(IllegalArgumentException.class,
                () -> TaskSettings.requireFullReasoning(task(true), ExecutionMode.UNCHARTED, excluded));
    }

    @Test
    void openRouterRequestsAskForTheReasoning() {
        LlmClientConfig config = LlmClientConfig.fromJson(openRouter(pinned()).getJsonObject("llm")).withReasoningRequested();

        assertEquals(new JsonObject().put("enabled", true).put("exclude", false), config.extraBody().getJsonObject("reasoning"));
        assertEquals(true, config.extraBody().getJsonObject("provider").getBoolean("require_parameters"));
        assertEquals(new JsonArray().add("DeepInfra"), config.extraBody().getJsonObject("provider").getJsonArray("only"));

        LlmClientConfig local = LlmClientConfig.defaults();
        assertSame(local, local.withReasoningRequested(), "other servers' requests are left alone");

        JsonObject own = openRouter(pinned());
        own.getJsonObject("llm").getJsonObject("extra_body").put("reasoning", new JsonObject().put("effort", "high"));
        assertEquals(new JsonObject().put("effort", "high"),
                LlmClientConfig.fromJson(own.getJsonObject("llm")).withReasoningRequested().extraBody().getJsonObject("reasoning"),
                "a request's own reasoning settings are kept");
    }

    @Test
    void saveTrajectoriesMustBeABoolean() {
        assertFalse(TaskSettings.saveTrajectories(new JsonObject()));
        assertTrue(TaskSettings.saveTrajectories(new JsonObject().put("saveTrajectories", true)));
        assertThrows(IllegalArgumentException.class, () -> TaskSettings.saveTrajectories(new JsonObject().put("saveTrajectories", "on")));
    }
}
