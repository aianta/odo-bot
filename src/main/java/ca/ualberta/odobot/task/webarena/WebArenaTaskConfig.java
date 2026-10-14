package ca.ualberta.odobot.task.webarena;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A task's WebArena config, from the task's {@code webarena} block, which the scheduler builds from WebArena's
 * {@code test.raw.json}:
 * <pre>
 * "webarena": {"taskId": 308, "intentTemplateId": 303, "intent": "...", "sites": ["gitlab"],
 *              "storageState": "./.auth/gitlab_state.json", "startUrls": ["http://..."], "eval": {...}}
 * </pre>
 * The site placeholders ({@code __GITLAB__} etc.) must already be replaced with the task's site URLs, the ones its
 * browser uses, as WebArena's {@code generate_test_data.py} replaces them. The evaluators only read {@code intent} and
 * {@code eval}.
 *
 * @param config {@code intent} and {@code eval}, as the evaluators read them.
 */
public record WebArenaTaskConfig(Integer taskId, String intent, List<String> sites, String storageState, JsonObject config) {

    public static final String FIELD = "webarena";

    /**
     * Site placeholders of test.raw.json. {@code __last_url__} and {@code __page__} are the evaluators' own and stay.
     */
    private static final Pattern SITE_PLACEHOLDER = Pattern.compile("__(SHOPPING_ADMIN|SHOPPING|GITLAB|REDDIT|MAP|WIKIPEDIA|HOMEPAGE)__");

    /**
     * @return the task's WebArena config, or null if the task has none, in which case it isn't scored this way.
     * @throws IllegalArgumentException if the block is malformed or still holds a site placeholder.
     */
    public static WebArenaTaskConfig fromTask(JsonObject task){
        Object raw = task.getValue(FIELD);
        if(raw == null){
            return null;
        }
        if(!(raw instanceof JsonObject block)){
            throw new IllegalArgumentException("'%s' must be an object.".formatted(FIELD));
        }
        JsonObject eval = block.getJsonObject("eval");
        if(eval == null || !(eval.getValue("eval_types") instanceof JsonArray)){
            throw new IllegalArgumentException("'%s.eval' must be WebArena's eval block, with its eval_types.".formatted(FIELD));
        }
        Matcher leftover = SITE_PLACEHOLDER.matcher(block.encode());
        if(leftover.find()){
            throw new IllegalArgumentException("'%s' still holds the placeholder %s: replace it with the task's site URL.".formatted(FIELD, leftover.group()));
        }
        String intent = block.getString("intent");
        if(intent == null){
            throw new IllegalArgumentException("'%s.intent' is missing; the judge needs WebArena's intent, not the task text.".formatted(FIELD));
        }
        JsonArray sites = block.getJsonArray("sites", new JsonArray());
        return new WebArenaTaskConfig(
                block.getInteger("taskId"),
                intent,
                sites.stream().map(String::valueOf).toList(),
                block.getString("storageState"),
                new JsonObject().put("intent", intent).put("eval", eval.copy()));
    }

    public List<String> evalTypes(){
        return config.getJsonObject("eval").getJsonArray("eval_types").stream().map(String::valueOf).toList();
    }
}
