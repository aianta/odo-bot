package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.instructions.TaskComplete;
import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When the agent completes a task, its raw events are still there for the harness to save.
 */
class RequestManagerTaskCompleteTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private Context context;
    private OdoClient client;
    private final RequestManagerStepLimitTest.ScriptedAgent agent = new RequestManagerStepLimitTest.ScriptedAgent();

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        context = vertx.getOrCreateContext();
        client = new OdoClient(UUID.randomUUID(), vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void theRawEventsOutliveTheTasksCompletion() throws Exception {
        Path saved = dir.resolve("task.json");
        Promise<Void> evaluation = Promise.promise();
        OnlineEventProcessor events = client.getEventConnectionManager().getEventProcessor();

        run(()->{
            RequestManager requestManager = client.getRequestManager();
            requestManager.setEvalId("eval-1");
            requestManager.setEvaluationComplete(evaluation);
            //Like TaskVerticle: save the events when the evaluation completes.
            evaluation.future().onComplete(done->events.saveRawEvents(saved.toString()));
            requestManager.startTask(agent, ExecutionMode.UNCHARTED, UUID.randomUUID(), 60000);

            events.process(new JsonObject().put("eventType", "otherEvent").put("eventDetails", new JsonObject().put("name", "TEST")));
            agent.emit.accept(new TaskComplete("42"));
        });

        assertTrue(evaluation.future().succeeded());
        JsonArray savedEvents = new JsonArray(Buffer.buffer(Files.readAllBytes(saved)));
        assertEquals(2, savedEvents.size(), "the event and the agent's answer");
        assertEquals("TEST", savedEvents.getJsonObject(0).getJsonObject("eventDetails").getString("name"));

        //Completing the task doesn't clear them either: the harness does, once they are saved.
        events.saveRawEvents(saved.toString());
        assertEquals(2, new JsonArray(Buffer.buffer(Files.readAllBytes(saved))).size());
    }

    private void run(Runnable action) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        context.runOnContext(v->{
            try{
                action.run();
                done.complete(null);
            }catch (Throwable t){
                done.completeExceptionally(t);
            }
        });
        done.get(5, TimeUnit.SECONDS);
    }
}
