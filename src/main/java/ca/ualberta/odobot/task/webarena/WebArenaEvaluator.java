package ca.ualberta.odobot.task.webarena;

import io.vertx.core.json.JsonObject;

/**
 * One of WebArena's evaluators ({@code evaluation_harness/evaluators.py}).
 */
public interface WebArenaEvaluator {

    /**
     * @return the {@code eval_types} entry this evaluator implements.
     */
    String evalType();

    /**
     * Score the end of a task, as the original's {@code __call__}.
     *
     * @param trace where to record how the score was reached, for the task's evaluation file.
     * @return the score, 1.0 for a pass.
     * @throws Exception where the original raises, which makes the task's evaluation an ERROR.
     */
    double evaluate(WebArenaEndState end, JsonObject trace) throws Exception;
}
