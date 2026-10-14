package ca.ualberta.odobot.task.webarena;

import ca.ualberta.odobot.common.LlmCallScope;
import ca.ualberta.odobot.guidance.TokenUsageRecord;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Java side of the live parity checks in scripts/webarena-parity (see its README). It runs without OdoBot's
 * harness or an agent:
 *
 * <ul>
 *     <li>{@code sweep <sweep.json> <out.json>}: for each task of a sweep file (made by make_sweep.py), open a fresh
 *     Firefox, log in to the task's sites as WebArena does, go to the task's page, and score the given answer with
 *     the ported evaluators, recording every value read off the pages. sweep_python.py does the same with WebArena's
 *     own code, and compare.py diffs the two.</li>
 *     <li>{@code judge <cases.json> <out.json>}: send judge cases (made by judge_agreement.py) to the configured judge
 *     and record its replies and scores.</li>
 *     <li>{@code values <values.json> <out.json>}: evaluate JS expressions (made by js_values.py) on a page and record
 *     what Python's {@code str()} of Playwright's result would be.</li>
 * </ul>
 */
public final class ParityRunner {

    private ParityRunner(){}

    public static void main(String[] args) throws Exception {
        if(args.length != 3){
            System.err.println("usage: ParityRunner sweep|judge|values <in.json> <out.json>");
            System.exit(2);
        }
        JsonObject in = new JsonObject(Files.readString(Path.of(args[1])));
        JsonObject out = switch (args[0]){
            case "sweep" -> sweep(in);
            case "judge" -> judge(in);
            case "values" -> values(in);
            default -> throw new IllegalArgumentException("unknown mode " + args[0]);
        };
        Files.writeString(Path.of(args[2]), out.encodePrettily());
        System.out.println("wrote " + args[2]);
        System.exit(0);
    }

    private static WebDriver browser(JsonObject in) throws Exception {
        FirefoxOptions options = new FirefoxOptions();
        if(in.getBoolean("headless", true)){
            options.addArguments("--headless");
        }
        String grid = in.getString("gridUrl");
        return grid == null? new FirefoxDriver(options) : new RemoteWebDriver(new URL(grid), options);
    }

    /**
     * Sweep file: {@code {gridUrl, headless, sites: {name: url}, accounts: {...}, pageSettleMs, judgeReply,
     * tasks: [{taskId, loginSites: [...], startUrl, answer, config: {intent, eval}}]}}.
     */
    static JsonObject sweep(JsonObject in) throws Exception {
        Map<String, String> sites = new LinkedHashMap<>();
        in.getJsonObject("sites").forEach(e->sites.put(e.getKey(), String.valueOf(e.getValue())));
        WebArenaAccounts accounts = WebArenaAccounts.fromJson(in.getJsonObject("accounts"));
        long settle = in.getLong("pageSettleMs", WebArenaEndState.DEFAULT_PAGE_SETTLE_MS);

        JsonArray results = new JsonArray();
        for(Object o: in.getJsonArray("tasks")){
            JsonObject task = (JsonObject) o;
            JsonObject result = new JsonObject().put("taskId", task.getValue("taskId"));
            results.add(result);
            WebDriver driver = browser(in);
            try{
                List<TaskAuth.Site> login = task.getJsonArray("loginSites", new JsonArray()).stream()
                        .map(String::valueOf)
                        .map(site->new TaskAuth.Site(site, sites.get(site), null, accounts.get(site).username(),
                                accounts.get(site).password(), true, null))
                        .toList();
                result.put("auth", SessionAuthenticator.authenticate(driver, new TaskAuth(TaskAuth.SESSION, login)));
                driver.get(task.getString("startUrl"));

                HttpWebArenaBackend backend = new HttpWebArenaBackend(sites.get("shopping"), accounts.get(WebArenaAccounts.SHOPPING_SITE_ADMIN));
                WebArenaPage page = new SeleniumWebArenaPage(driver);
                result.put("startUrl", page.url());
                JsonObject config = task.getJsonObject("config");
                //The judge is checked on its own (judge mode); the sweep stubs it with the same reply as the Python side.
                String reply = in.getString("judgeReply");
                WebArenaJudge judge = reply == null? null : messages->reply;
                WebArenaEndState end = new WebArenaEndState(config, task.getString("answer", ""), page, backend,
                        judge, settle, Thread::sleep);
                JsonObject trace = new JsonObject();
                try{
                    result.put("score", WebArenaEvaluators.evaluate(config, end, trace));
                }catch (Exception e){
                    result.put("error", e.toString());
                }
                result.put("trace", trace).put("backendCalls", new JsonArray(backend.calls()));
            }catch (Exception e){
                result.put("error", "setup: " + e);
            }finally {
                driver.quit();
            }
            System.out.println("task " + task.getValue("taskId") + ": " + result.getValue("score", result.getValue("error")));
        }
        return new JsonObject().put("side", "java").put("results", results);
    }

    /**
     * Values file: {@code {gridUrl, headless, url, expressions: [...]}}.
     */
    static JsonObject values(JsonObject in) throws Exception {
        WebDriver driver = browser(in);
        JsonArray results = new JsonArray();
        try{
            driver.get(in.getString("url"));
            WebArenaPage page = new SeleniumWebArenaPage(driver);
            for(Object expression: in.getJsonArray("expressions")){
                JsonObject r = new JsonObject().put("expression", expression);
                try{
                    r.put("str", ca.ualberta.odobot.task.webarena.py.PyRepr.str(page.evaluate(String.valueOf(expression))));
                }catch (Exception e){
                    r.put("error", e.getMessage());
                }
                results.add(r);
            }
        }finally {
            driver.quit();
        }
        return new JsonObject().put("side", "java").put("results", results);
    }

    /**
     * Judge cases: {@code {judge: {...LlmClientConfig json}, cases: [{id, kind: fuzzy|ua, pred, reference, question}]}}.
     */
    static JsonObject judge(JsonObject in){
        WebArenaEvaluationSettings settings = WebArenaEvaluationSettings.fromRequest(
                new JsonObject().put(WebArenaEvaluationSettings.FIELD, new JsonObject().put("judge", in.getJsonObject("judge", new JsonObject()))));
        TokenUsageRecord usage = new TokenUsageRecord();
        LlmCallScope scope = new LlmCallScope();
        scope.start(usage, settings.judge());
        OpenAiWebArenaJudge judge = new OpenAiWebArenaJudge(settings.judge(), scope);

        JsonArray results = new JsonArray();
        for(Object o: in.getJsonArray("cases")){
            JsonObject c = (JsonObject) o;
            boolean ua = "ua".equals(c.getString("kind"));
            List<WebArenaJudge.Message> messages = ua
                    ? WebArenaJudge.uaMatchMessages(c.getString("pred"), c.getString("reference"), c.getString("question"))
                    : WebArenaJudge.fuzzyMatchMessages(c.getString("pred"), c.getString("reference"), c.getString("question"));
            JsonObject r = new JsonObject().put("id", c.getValue("id"));
            try{
                String reply = judge.complete(messages);
                r.put("reply", reply).put("score", ua? WebArenaJudge.parseUaMatch(reply) : WebArenaJudge.parseFuzzyMatch(reply));
            }catch (Exception e){
                r.put("error", e.toString());
            }
            results.add(r);
        }
        return new JsonObject().put("side", "java").put("model", settings.judge().model())
                .put("usage", usage.toJson()).put("results", results);
    }
}
