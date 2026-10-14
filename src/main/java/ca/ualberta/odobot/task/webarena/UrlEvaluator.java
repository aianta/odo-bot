package ca.ualberta.odobot.task.webarena;

import ca.ualberta.odobot.task.webarena.py.PyStrings;
import ca.ualberta.odobot.task.webarena.py.PyUrl;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WebArena's {@code URLEvaluator} ({@code url_match}): the page's URL against the config's {@code reference_url}, under
 * the only matching rule the original has, "GOLD in PRED".
 */
public class UrlEvaluator implements WebArenaEvaluator {

    @Override
    public String evalType() {
        return "url_match";
    }

    static String cleanUrl(String url){
        return PyStrings.rstrip(url, "/");
    }

    /**
     * @return netloc + path, and the parsed query.
     */
    private static Map.Entry<String, Map<String, List<String>>> parseUrl(String url){
        PyUrl.ParseResult parsed = PyUrl.urlparse(url);
        return Map.entry(parsed.netloc() + parsed.path(), PyUrl.parseQs(parsed.query()));
    }

    /**
     * Score a URL against the reference, without a page.
     */
    public static double score(String pageUrl, String referenceUrl, String matchingRule, JsonObject trace){
        String pred = cleanUrl(pageUrl);
        List<String> refUrls = new ArrayList<>();
        for(String u: referenceUrl.split(" \\|OR\\| ", -1)){
            refUrls.add(cleanUrl(u));
        }
        trace.put("pred", pred).put("refs", new JsonArray(refUrls)).put("matchingRule", matchingRule);
        if(!"GOLD in PRED".equals(matchingRule)){
            throw new IllegalArgumentException("Unknown matching rule: " + matchingRule);
        }

        List<String> refBasePaths = new ArrayList<>();
        Map<String, Set<String>> refQueries = new LinkedHashMap<>();
        for(String u: refUrls){
            Map.Entry<String, Map<String, List<String>>> parsed = parseUrl(u);
            refBasePaths.add(parsed.getKey());
            parsed.getValue().forEach((k, v)->refQueries.computeIfAbsent(k, key->new LinkedHashSet<>()).addAll(v));
        }
        Map.Entry<String, Map<String, List<String>>> predParsed = parseUrl(pred);
        //The original tests 'ref_base_path in pred_base_paths', where the latter is a string: a substring test.
        String predBasePath = predParsed.getKey();
        double baseScore = refBasePaths.stream().anyMatch(predBasePath::contains)? 1.0 : 0.0;

        double queryScore = 1.0;
        for(Map.Entry<String, Set<String>> e: refQueries.entrySet()){
            List<String> predValues = predParsed.getValue().getOrDefault(e.getKey(), List.of());
            queryScore *= e.getValue().stream().anyMatch(predValues::contains)? 1.0 : 0.0;
        }
        trace.put("baseScore", baseScore).put("queryScore", queryScore);
        return baseScore * queryScore;
    }

    @Override
    public double evaluate(WebArenaEndState end, JsonObject trace) {
        JsonObject eval = end.eval();
        //Python's .get("url_note", "GOLD in PRED"): a url_note that is present but null is an unknown rule.
        String matchingRule = eval.containsKey("url_note")? eval.getString("url_note") : "GOLD in PRED";
        return score(end.page().url(), eval.getString("reference_url"), matchingRule, trace);
    }
}
