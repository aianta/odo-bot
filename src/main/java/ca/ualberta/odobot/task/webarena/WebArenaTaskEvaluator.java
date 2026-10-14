package ca.ualberta.odobot.task.webarena;

import ca.ualberta.odobot.common.LlmCallScope;
import ca.ualberta.odobot.guidance.TokenUsageRecord;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Scores one WebArena task at its end, while its browser is still open, as WebArena's {@code run.py} does after the
 * agent stops: route the config's {@code eval_types} to the evaluators and multiply their scores. The task passes when
 * the score is 1.
 *
 * <p>An evaluator that throws makes the result ERROR, as WebArena's "Unhandled Error", which leaves the task out of its
 * scores. Everything the evaluators did is written to {@code <evalId>-webarena-eval.json}.</p>
 *
 * <p>Blocking: run it on a worker thread.</p>
 */
public class WebArenaTaskEvaluator {

    private static final Logger log = LoggerFactory.getLogger(WebArenaTaskEvaluator.class);

    public static final String FILE_SUFFIX = "-webarena-eval.json";

    /**
     * The outcome of scoring a task.
     *
     * @param result PASS, FAIL or ERROR.
     * @param score the product of the evaluators' scores, or null for an ERROR.
     * @param finalUrl the URL of the agent's tab before the evaluators navigated, or null if it could not be read.
     * @param judgeUsage the judge's token usage, or null if the task has no judge.
     */
    public record Result(String result, Double score, String error, String answer, String finalUrl, JsonObject judgeUsage){

        public JsonObject summary(){
            JsonObject summary = new JsonObject().put("result", result).put("score", score);
            if(error != null){
                summary.put("error", error);
            }
            return summary;
        }
    }

    private final WebArenaTaskConfig config;
    private final WebArenaEvaluationSettings settings;
    private final Map<String, String> sites;
    private final WebArenaAccounts accounts;

    /**
     * @param sites the task environment's named site URLs; {@code shopping} is the base of the REST helpers.
     * @param accounts the accounts to use, with the task's own credentials in place of the defaults.
     */
    public WebArenaTaskEvaluator(WebArenaTaskConfig config, WebArenaEvaluationSettings settings, Map<String, String> sites,
                                 WebArenaAccounts accounts){
        this.config = config;
        this.settings = settings;
        this.sites = sites;
        this.accounts = accounts;
    }

    /**
     * WebArena's answer for the agent's end of the task: what the agent reported; "N/A" if it judged the task
     * infeasible without saying why, as WebArena agents answer {@code stop [N/A]}; otherwise "", as {@code run.py}
     * appends {@code stop("")} when an agent runs out of steps.
     */
    public static String webArenaAnswer(String agentAnswer, boolean judgedInfeasible){
        if(agentAnswer != null){
            return agentAnswer;
        }
        return judgedInfeasible? "N/A" : "";
    }

    /**
     * Score the task and write its evaluation file.
     *
     * @param driver the task's browser, already switched to the agent's tab.
     * @param droppedEvents how many OdoX events were dropped while scoring, read once scoring is done.
     * @param evaluationFile where to write the evaluation, {@code <evalId>-webarena-eval.json}.
     */
    public Result evaluate(String evalId, WebDriver driver, String agentAnswer, boolean judgedInfeasible,
                           Supplier<Integer> droppedEvents, Path evaluationFile){
        String answer = webArenaAnswer(agentAnswer, judgedInfeasible);
        JsonObject trace = new JsonObject();
        HttpWebArenaBackend backend = new HttpWebArenaBackend(sites.get("shopping"), accounts.get(WebArenaAccounts.SHOPPING_SITE_ADMIN));

        TokenUsageRecord judgeUsage = null;
        OpenAiWebArenaJudge judge = null;
        if(settings.judge() != null){
            judgeUsage = new TokenUsageRecord();
            LlmCallScope scope = new LlmCallScope();
            scope.start(judgeUsage, settings.judge());
            judge = new OpenAiWebArenaJudge(settings.judge(), scope);
        }

        long started = System.nanoTime();
        String finalUrl = null;
        Double score = null;
        String result;
        String error = null;
        try{
            WebArenaPage page = new SeleniumWebArenaPage(driver);
            finalUrl = page.url();
            WebArenaEndState end = new WebArenaEndState(config.config(), answer, page, backend, judge,
                    settings.pageSettleMs(), Thread::sleep);
            score = WebArenaEvaluators.evaluate(config.config(), end, trace);
            result = score == 1.0? "PASS" : "FAIL";
        }catch (Exception e){
            log.warn("Task {}: WebArena evaluation failed: {}", evalId, e.toString(), e);
            result = "ERROR";
            error = e.toString();
        }
        long durationMs = (System.nanoTime() - started) / 1_000_000;

        JsonObject judgeUsageJson = judgeUsage == null? null : judgeUsage.toJson();
        JsonObject file = new JsonObject()
                .put("evalId", evalId)
                .put("webarenaTaskId", config.taskId())
                .put("result", result)
                .put("score", score)
                .put("error", error)
                .put("answer", answer)
                .put("agentAnswer", agentAnswer)
                .put("judgedInfeasible", judgedInfeasible)
                .put("finalUrl", finalUrl)
                .put("evalTypes", new io.vertx.core.json.JsonArray(config.evalTypes()))
                .put("durationMs", durationMs)
                .put("pageSettleMs", settings.pageSettleMs())
                .put("judgeModel", settings.judge() == null? null : settings.judge().model())
                .put("judgeUsage", judgeUsageJson)
                .put("backendCalls", new io.vertx.core.json.JsonArray(backend.calls()))
                .put("droppedEvalEvents", droppedEvents.get())
                .mergeIn(trace);
        try{
            Files.writeString(evaluationFile, file.encodePrettily());
        }catch (IOException e){
            log.error("Task {}: could not write {}: {}", evalId, evaluationFile, e.getMessage());
        }
        log.info("Task {}: WebArena {} (score {}) in {} ms", evalId, result, score, durationMs);
        return new Result(result, score, error, answer, finalUrl, judgeUsageJson);
    }
}
