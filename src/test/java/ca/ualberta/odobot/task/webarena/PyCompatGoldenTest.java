package ca.ualberta.odobot.task.webarena;

import ca.ualberta.odobot.task.webarena.py.NltkWordTokenizer;
import ca.ualberta.odobot.task.webarena.py.PyHtml;
import ca.ualberta.odobot.task.webarena.py.PyRepr;
import ca.ualberta.odobot.task.webarena.py.PyStrings;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The Python-compatible helpers give exactly what CPython, NLTK and Playwright give, over the golden files generated
 * from them (scripts/webarena-parity/gen_golden.py).
 */
class PyCompatGoldenTest {

    @Test
    void stringFunctionsMatchStringEvaluator(){
        Golden.Mismatches m = new Golden.Mismatches("string function");
        for(Object o: Golden.load("string-functions")){
            JsonObject c = (JsonObject) o;
            String ref = c.getString("ref");
            String pred = c.getString("pred");
            String where = "ref=%s pred=%s".formatted(PyRepr.reprString(ref), PyRepr.reprString(pred));
            m.check(PyStrings.cleanAnswer(ref).equals(c.getString("clean_ref")), "clean_answer " + where);
            m.check(PyStrings.cleanAnswer(pred).equals(c.getString("clean_pred")), "clean_answer " + where);
            m.check(StringEvaluator.exactMatch(ref, pred) == c.getDouble("exact_match"), "exact_match " + where);
            m.check(StringEvaluator.mustInclude(ref, pred, false) == c.getDouble("must_include"), "must_include " + where);
            m.check(StringEvaluator.mustInclude(ref, pred, true) == c.getDouble("must_include_tokenize"),
                    "must_include(tokenize) " + where);
        }
        m.assertNone();
    }

    @Test
    void wordTokenizeMatchesNltk(){
        Golden.Mismatches m = new Golden.Mismatches("word_tokenize");
        for(Object o: Golden.load("word-tokenize")){
            JsonObject c = (JsonObject) o;
            List<String> expected = c.getJsonArray("tokens").stream().map(String::valueOf).toList();
            List<String> actual = NltkWordTokenizer.wordTokenize(c.getString("text"));
            m.check(actual.equals(expected), "%s: expected %s, got %s".formatted(PyRepr.reprString(c.getString("text")), expected, actual));
        }
        m.assertNone();
    }

    @Test
    void unescapeMatchesCPython(){
        Golden.Mismatches m = new Golden.Mismatches("html.unescape");
        for(Object o: Golden.load("unescape")){
            JsonObject c = (JsonObject) o;
            String actual = PyHtml.unescape(c.getString("input"));
            m.check(actual.equals(c.getString("expected")), "%s: expected %s, got %s".formatted(
                    PyRepr.reprString(c.getString("input")), PyRepr.reprString(c.getString("expected")), PyRepr.reprString(actual)));
        }
        m.assertNone();
    }

    @Test
    void playwrightValuesPrintAsPythonStr(){
        Golden.Mismatches m = new Golden.Mismatches("str(page.evaluate(...))");
        for(Object o: Golden.load("py-values")){
            JsonObject c = (JsonObject) o;
            String actual;
            try{
                actual = PyRepr.str(PyRepr.fromPlaywright(c.getString("wire")));
            }catch (RuntimeException e){
                actual = "<" + e + ">";
            }
            m.check(actual.equals(c.getString("str")), "%s: expected %s, got %s".formatted(c.getString("wire"), c.getString("str"), actual));
        }
        m.assertNone();
    }

    @Test
    void redditPostUrlMatchesHelper(){
        Golden.Mismatches m = new Golden.Mismatches("reddit_get_post_url");
        for(Object o: Golden.load("reddit-post-url")){
            JsonObject c = (JsonObject) o;
            String actual = WebArenaHelpers.redditGetPostUrl(c.getString("url"));
            m.check(actual.equals(c.getString("expected")), "%s: expected %s, got %s".formatted(c.getString("url"), c.getString("expected"), actual));
        }
        m.assertNone();
    }

    @Test
    void funcCallsParseAsPythonDoes(){
        Golden.Mismatches m = new Golden.Mismatches("func: call");
        for(Object o: Golden.load("func-calls")){
            JsonObject c = (JsonObject) o;
            FuncCall call = FuncCall.parse(c.getString("source"));
            JsonArray expected = c.getJsonArray("args");
            List<String> actualArgs = call.args().stream().map(String::valueOf).toList();
            List<String> expectedArgs = expected.stream().map(String::valueOf).toList();
            m.check(call.name().equals(c.getString("name")) && actualArgs.equals(expectedArgs),
                    "%s: expected %s%s, got %s%s".formatted(c.getString("source"), c.getString("name"), expectedArgs, call.name(), actualArgs));
        }
        m.assertNone();
    }
}
