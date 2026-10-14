package ca.ualberta.odobot.task.webarena;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The cases of WebArena's own evaluator tests (tests/test_evaluation_harness, removed upstream in ebe0d7b) that don't
 * need a live page. The others read russmaxdesign.github.io, google.com or WebArena sites, and are covered by the live
 * parity sweep (scripts/webarena-parity).
 */
class UpstreamEvaluatorTest {

    private static WebArenaEndState end(JsonObject eval, String answer, String url){
        WebArenaPage page = new EvaluatorGoldenTest.ScriptedPage(url, new JsonObject());
        return new WebArenaEndState(new JsonObject().put("intent", "").put("eval", eval), answer, page, null, null, 0, ms->{});
    }

    private static JsonObject stringMatchConfig(){
        return new JsonObject()
                .put("eval_types", new JsonArray().add("string_match"))
                .put("reference_answers", new JsonObject().put("must_include", new JsonArray().add("1985/04/18")))
                .put("reference_url", "")
                .putNull("program_html");
    }

    private static JsonObject urlExactMatchConfig(){
        return new JsonObject()
                .put("eval_types", new JsonArray().add("url_match"))
                .put("reference_answers", new JsonArray())
                .put("reference_url", "https://www.google.com/");
    }

    @Test
    void stringMatchSuccess() throws Exception {
        assertEquals(1.0, new StringEvaluator().evaluate(end(stringMatchConfig(), "The date is 1985/04/18", ""), new JsonObject()));
    }

    @Test
    void stringMatchFail() throws Exception {
        assertEquals(0.0, new StringEvaluator().evaluate(end(stringMatchConfig(), "The date is 1936/04/18", ""), new JsonObject()));
    }

    @Test
    void urlExactMatchSuccess() {
        assertEquals(1.0, new UrlEvaluator().evaluate(end(urlExactMatchConfig(), "", "https://www.google.com/"), new JsonObject()));
    }

    @Test
    void urlExactMatchFail() {
        assertEquals(0.0, new UrlEvaluator().evaluate(end(urlExactMatchConfig(), "", "http://gitlab.test:8023"), new JsonObject()));
    }

    @Test
    void htmlContentUrlCombinationMultipliesScores() throws Exception {
        JsonObject eval = new JsonObject()
                .put("eval_types", new JsonArray().add("program_html").add("url_match"))
                .put("reference_url", "https://russmaxdesign.github.io/")
                .put("url_note", "GOLD in PRED")
                .put("program_html", new JsonArray()
                        .add(new JsonObject().put("url", "last").put("locator", "document.querySelector('[id=\"form-name\"').value")
                                .put("required_contents", new JsonObject().put("must_include", new JsonArray().add("Hello World"))))
                        .add(new JsonObject().put("url", "last").put("locator", "document.querySelector('[id=\"form-email\"').value")
                                .put("required_contents", new JsonObject().put("must_include", new JsonArray().add("alexisxy@hotmail.com")))));
        JsonObject script = new JsonObject().put("content", "").put("evaluate", new JsonArray()
                .add(new JsonArray().add("form-name").add("Hello World"))
                .add(new JsonArray().add("form-email").add("alexisxy@hotmail.com")));
        EvaluatorGoldenTest.ScriptedPage page = new EvaluatorGoldenTest.ScriptedPage("https://russmaxdesign.github.io/exercise/", script);
        JsonObject config = new JsonObject().put("intent", "").put("eval", eval);
        WebArenaEndState end = new WebArenaEndState(config, "", page, null, null, 0, ms->{});

        assertEquals(1.0, WebArenaEvaluators.evaluate(config, end, new JsonObject()));

        page.url = "https://www.google.com/";
        assertEquals(0.0, WebArenaEvaluators.evaluate(config, end, new JsonObject()), "the URL no longer matches");
    }
}
