package ca.ualberta.odobot.task.webarena;

import ca.ualberta.odobot.common.LlmClientConfig;
import ca.ualberta.odobot.guidance.OnlineEventProcessor;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The parts of WebArena scoring that sit in the task's lifecycle: the answer the evaluators see, the configs they read,
 * and the event log they must not touch.
 */
class WebArenaLifecycleTest {

    @Test
    void theAnswerIsWhatTheAgentReportedOrWebArenasDefault(){
        assertEquals("42", WebArenaTaskEvaluator.webArenaAnswer("42", false));
        assertEquals("No such repo", WebArenaTaskEvaluator.webArenaAnswer("No such repo", true));
        assertEquals("N/A", WebArenaTaskEvaluator.webArenaAnswer(null, true), "infeasible without a reason is stop [N/A]");
        assertEquals("", WebArenaTaskEvaluator.webArenaAnswer(null, false), "step limit, timeout or error is stop(\"\")");
    }

    @Test
    void sealedEventsAreCountedAndDropped() throws Exception {
        OnlineEventProcessor events = new OnlineEventProcessor();
        Path part = Files.createTempFile("events", ".jsonl.part");
        events.streamRawEventsTo(part);
        JsonObject event = new JsonObject().put("eventType", "customEvent")
                .put("eventDetails", new JsonObject().put("name", "SOMETHING"));
        events.process(event);
        Path saved = Files.createTempFile("events", ".json");
        events.saveRawEvents(saved.toString());
        events.clearRawEvents();
        events.seal();

        events.process(event.copy());
        events.process(event.copy());

        assertEquals(2, events.droppedAfterSeal());
        assertEquals(1, new JsonArray(Files.readString(saved)).size(), "the saved log keeps only the task's events");
        Path again = Files.createTempFile("events", ".json");
        events.saveRawEvents(again.toString());
        assertEquals(0, new JsonArray(Files.readString(again)).size(), "nothing is buffered after the seal");
    }

    private static JsonObject task(JsonObject eval){
        return new JsonObject().put("webarena", new JsonObject()
                .put("taskId", 308).put("intent", "Who made the most commits?")
                .put("sites", new JsonArray().add("gitlab")).put("eval", eval));
    }

    @Test
    void aTaskConfigNeedsItsPlaceholdersReplaced(){
        JsonObject eval = new JsonObject().put("eval_types", new JsonArray().add("url_match"))
                .put("reference_url", "__GITLAB__/primer/design");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ()->WebArenaTaskConfig.fromTask(task(eval)));
        assertTrue(e.getMessage().contains("__GITLAB__"), e.getMessage());

        eval.put("reference_url", "http://gitlab:8023/primer/design")
                .put("program_html", new JsonArray().add(new JsonObject().put("url", "func:reddit_get_post_url('__last_url__')")));
        WebArenaTaskConfig config = WebArenaTaskConfig.fromTask(task(eval));
        assertEquals(308, config.taskId());
        assertEquals("Who made the most commits?", config.config().getString("intent"));
        assertNull(WebArenaTaskConfig.fromTask(new JsonObject()), "a task without a webarena block isn't scored this way");
    }

    @Test
    void theJudgeDefaultsToWebArenasSettings(){
        WebArenaEvaluationSettings settings = WebArenaEvaluationSettings.fromRequest(new JsonObject()
                .put("webarenaEvaluation", new JsonObject().put("judge", new JsonObject().put("api_key", "k"))));
        LlmClientConfig judge = settings.judge();
        assertEquals("gpt-4-1106-preview", judge.model());
        assertNull(judge.baseUrl(), "the OpenAI API");
        assertEquals(0.0, judge.temperature());
        assertEquals(1.0, judge.topP());
        assertEquals(768, judge.maxTokens());
        assertNull(judge.topK(), "not sent");
        assertEquals(3000, settings.pageSettleMs());

        assertNull(WebArenaEvaluationSettings.fromRequest(new JsonObject()).judge(), "no judge unless configured");
        assertEquals("override", WebArenaEvaluationSettings.fromRequest(new JsonObject().put("webarenaEvaluation", new JsonObject()
                .put("judge", new JsonObject().put("model", "override")))).judge().model());
    }

    @Test
    void sessionAuthLogsInOnlyTheSitesThatStartLoggedIn(){
        JsonObject auth = new JsonObject().put("mode", "session").put("sites", new JsonArray()
                .add(new JsonObject().put("site", "gitlab").put("baseUrl", "http://g:8023").put("username", "byteblaze")
                        .put("password", "pw").put("loggedInAtStart", true))
                .add(new JsonObject().put("site", "reddit").put("baseUrl", "http://r").put("username", "u")
                        .put("password", "p").put("loggedInAtStart", false)));
        TaskAuth parsed = TaskAuth.fromJson(auth);
        assertEquals(1, parsed.sitesToLogIn().size());
        assertEquals("gitlab", parsed.sitesToLogIn().get(0).site());
        assertFalse(parsed.toJson().encode().contains("pw"), "passwords are not saved with the environment");
        assertEquals("pw", parsed.applyTo(WebArenaAccounts.defaults()).get("gitlab").password());

        assertTrue(TaskAuth.fromJson(auth.copy().put("mode", "prompt")).sitesToLogIn().isEmpty(), "the agent logs in itself");
        assertTrue(TaskAuth.fromJson(null).sitesToLogIn().isEmpty());
        assertThrows(IllegalArgumentException.class, ()->TaskAuth.fromJson(new JsonObject().put("mode", "magic")));
    }
}
