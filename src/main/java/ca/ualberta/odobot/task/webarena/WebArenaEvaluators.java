package ca.ualberta.odobot.task.webarena;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * WebArena's {@code evaluator_router} and {@code EvaluatorComb}: the evaluators of a task's {@code eval_types}, in that
 * order, whose scores are multiplied.
 */
public final class WebArenaEvaluators {

    private WebArenaEvaluators(){}

    /**
     * {@code evaluator_router(config)}.
     *
     * @throws IllegalArgumentException for an eval type the original doesn't support.
     */
    public static List<WebArenaEvaluator> route(JsonObject config){
        List<WebArenaEvaluator> evaluators = new ArrayList<>();
        for(Object type: config.getJsonObject("eval").getJsonArray("eval_types")){
            evaluators.add(switch (String.valueOf(type)){
                case "string_match" -> new StringEvaluator();
                case "url_match" -> new UrlEvaluator();
                case "program_html" -> new HtmlContentEvaluator();
                default -> throw new IllegalArgumentException("eval_type %s is not supported".formatted(type));
            });
        }
        return evaluators;
    }

    /**
     * {@code EvaluatorComb(...)(...)}: run every evaluator, in order, and multiply their scores. Like the original,
     * every evaluator runs even once the score is 0. An evaluator's exception ends the evaluation.
     *
     * @param trace gets one entry per evaluator under {@code evaluators}: its type, score and duration, plus what it
     *              recorded.
     */
    public static double evaluate(JsonObject config, WebArenaEndState end, JsonObject trace) throws Exception {
        JsonArray evaluatorsTrace = new JsonArray();
        trace.put("evaluators", evaluatorsTrace);
        double score = 1.0;
        for(WebArenaEvaluator evaluator: route(config)){
            JsonObject t = new JsonObject().put("type", evaluator.evalType());
            evaluatorsTrace.add(t);
            long started = System.nanoTime();
            try{
                double s = evaluator.evaluate(end, t);
                t.put("score", s);
                score *= s;
            }finally {
                t.put("durationMs", (System.nanoTime() - started) / 1_000_000);
            }
        }
        return score;
    }
}
