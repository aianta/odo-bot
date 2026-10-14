package ca.ualberta.odobot.task.webarena;

import io.vertx.core.json.JsonObject;

/**
 * What WebArena's evaluators read at the end of a task: the original's {@code trajectory} (only the answer of its
 * final stop action is read), {@code config_file} and {@code page}.
 *
 * @param config the task's WebArena config, with its {@code intent} and {@code eval} block, placeholders already
 *               replaced by the task's site URLs.
 * @param answer the agent's final answer: the stop action's answer in the original.
 * @param page the agent's tab.
 * @param backend the REST helpers, for the shopping {@code func:} calls.
 * @param judge the LLM judge, for fuzzy matches.
 * @param pageSettleMs how long program_html waits after navigating: the original's hard-coded {@code time.sleep(3)}.
 * @param sleeper how to wait, replaceable in tests.
 */
public record WebArenaEndState(JsonObject config, String answer, WebArenaPage page, WebArenaBackend backend,
                               WebArenaJudge judge, long pageSettleMs, Sleeper sleeper) {

    public static final long DEFAULT_PAGE_SETTLE_MS = 3000L;

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    public JsonObject eval(){
        return config.getJsonObject("eval");
    }
}
