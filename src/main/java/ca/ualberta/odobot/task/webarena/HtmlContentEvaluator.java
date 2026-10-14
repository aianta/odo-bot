package ca.ualberta.odobot.task.webarena;

import ca.ualberta.odobot.task.webarena.py.PyHtml;
import ca.ualberta.odobot.task.webarena.py.PyRepr;
import ca.ualberta.odobot.task.webarena.py.PyStrings;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * WebArena's {@code HTMLContentEvaluator} ({@code program_html}): for each target in the config's
 * {@code program_html}, open its URL, read a value off the page (the whole page, a JS locator, or a helper), and check
 * it holds the required contents.
 */
public class HtmlContentEvaluator implements WebArenaEvaluator {

    /**
     * How much of each selected value the trace keeps. The full value's SHA-256 is kept as well.
     */
    static final int TRACE_VALUE_CHARS = 4000;

    @Override
    public String evalType() {
        return "program_html";
    }

    /**
     * Python's {@code s.split(sep)[1]}.
     */
    private static String secondPart(String s, String sep){
        int first = s.indexOf(sep);
        if(first < 0){
            throw new IllegalArgumentException("list index out of range: no '%s' in '%s'".formatted(sep, s));
        }
        int start = first + sep.length();
        int next = s.indexOf(sep, start);
        return next < 0? s.substring(start) : s.substring(start, next);
    }

    @Override
    public double evaluate(WebArenaEndState end, JsonObject trace) throws Exception {
        WebArenaPage page = end.page();
        JsonArray targetsTrace = new JsonArray();
        trace.put("targets", targetsTrace);

        double score = 1.0;
        for(Object t: end.eval().getJsonArray("program_html")){
            JsonObject target = (JsonObject) t;
            JsonObject tt = new JsonObject();
            targetsTrace.add(tt);
            long started = System.nanoTime();

            String targetUrl = target.getString("url");
            if(targetUrl.startsWith("func")){
                String func = secondPart(targetUrl, "func:").replace("__last_url__", page.url());
                tt.put("urlFunc", func);
                targetUrl = (String) WebArenaHelpers.call(FuncCall.parse(func), page, end.backend());
            }
            tt.put("url", targetUrl);

            String locator = target.getString("locator");
            tt.put("locator", locator);

            if(!targetUrl.equals("last")){
                page.goTo(targetUrl);
                end.sleeper().sleep(end.pageSettleMs());
            }

            Object selected;
            if(PyStrings.strip(locator).isEmpty()){
                selected = page.content();
            }else if(locator.startsWith("document.") || locator.startsWith("[...document.")){
                if(target.containsKey("prep_actions")){
                    try{
                        for(Object prepAction: target.getJsonArray("prep_actions")){
                            page.evaluate("() => " + prepAction);
                        }
                    }catch (Exception e){
                        tt.put("prepActionError", e.getMessage());
                    }
                }
                try{
                    selected = PyRepr.str(page.evaluate("() => " + locator));
                }catch (Exception e){
                    //The page is wrong: the original returns "".
                    tt.put("locatorError", e.getMessage());
                    selected = "";
                }
            }else if(locator.startsWith("func:")){
                String func = secondPart(locator, "func:").replace("__page__", "page");
                tt.put("locatorFunc", func);
                selected = WebArenaHelpers.call(FuncCall.parse(func), page, end.backend());
            }else{
                throw new IllegalArgumentException("Unknown locator: " + locator);
            }

            if(!(selected instanceof String selectedString)){
                //html.unescape raises TypeError on anything but a str.
                throw new IllegalStateException("The locator gave %s, not a string".formatted(PyRepr.repr(selected)));
            }
            String selectedElement = PyHtml.unescape(selectedString);
            tt.put("selectedElement", selectedElement.length() > TRACE_VALUE_CHARS
                    ? selectedElement.substring(0, TRACE_VALUE_CHARS) : selectedElement);
            tt.put("selectedElementLength", selectedElement.length());
            tt.put("selectedElementSha256", sha256(selectedElement));

            JsonObject requiredContents = target.getJsonObject("required_contents");
            tt.put("requiredContents", requiredContents);
            double targetScore = 1.0;
            if(requiredContents.containsKey("exact_match")){
                targetScore = StringEvaluator.exactMatch(requiredContents.getString("exact_match"), selectedElement);
            }else if(requiredContents.containsKey("must_include")){
                Object mustInclude = requiredContents.getValue("must_include");
                if(!(mustInclude instanceof JsonArray contents)){
                    throw new IllegalArgumentException("'must_include' must be a list (the original asserts it)");
                }
                JsonArray each = new JsonArray();
                for(Object content: contents){
                    boolean any = false;
                    for(String alternative: ((String) content).split(" \\|OR\\| ", -1)){
                        if(StringEvaluator.mustInclude(alternative, selectedElement, false) == 1.0){
                            any = true;
                            break;
                        }
                    }
                    each.add(any);
                    targetScore *= any? 1.0 : 0.0;
                }
                tt.put("mustIncludeResults", each);
            }else{
                throw new IllegalArgumentException("Unknown required_contents: " + requiredContents.fieldNames());
            }
            score *= targetScore;
            tt.put("score", targetScore);
            tt.put("durationMs", (System.nanoTime() - started) / 1_000_000);
        }
        return score;
    }

    static String sha256(String s){
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        }catch (NoSuchAlgorithmException e){
            throw new IllegalStateException(e);
        }
    }
}
