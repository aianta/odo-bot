package ca.ualberta.odobot.task.webarena;

import ca.ualberta.odobot.task.webarena.py.PyRepr;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;

import java.util.Map;

/**
 * {@link WebArenaPage} over a Selenium driver focused on the agent's tab.
 */
public class SeleniumWebArenaPage implements WebArenaPage {

    /**
     * Runs the expression the way Playwright's utility script does (evaluate, call a function result, await a
     * promise), then serializes the result with Playwright's in-page serializer and returns it as JSON text.
     *
     * <p>The expression is spliced in as the right-hand side of an assignment statement rather than passed to
     * {@code eval}, so pages whose Content Security Policy forbids eval behave the same. Like Playwright's
     * {@code eval}, a trailing semicolon is accepted.</p>
     */
    private static final String EVALUATE_PREFIX = """
            var __done = arguments[arguments.length - 1];
            var __serialize = %s;
            var __finish = function(v){
              try { var s = __serialize(v); __done({ok: s === undefined ? null : JSON.stringify(s)}); }
              catch (e) { __done({err: 'serialize: ' + e}); }
            };
            var __fail = function(e){ __done({err: String(e && e.stack ? e + '\\n' + e.stack : e)}); };
            try {
              var __r;
              __r = """.formatted(PyRepr.PLAYWRIGHT_SERIALIZER_JS);

    private static final String EVALUATE_SUFFIX = """

              ;
              if (typeof __r === 'function') __r = __r();
              Promise.resolve(__r).then(__finish, __fail);
            } catch (e) { __fail(e); }
            """;

    private static final String CONTENT_JS = """
            let retVal = '';
            if (document.doctype) retVal = new XMLSerializer().serializeToString(document.doctype);
            if (document.documentElement) retVal += document.documentElement.outerHTML;
            return retVal;
            """;

    private final WebDriver driver;

    public SeleniumWebArenaPage(WebDriver driver){
        this.driver = driver;
    }

    @Override
    public String url() {
        return driver.getCurrentUrl();
    }

    @Override
    public void goTo(String url) {
        driver.get(url);
    }

    @Override
    public Object evaluate(String expression) throws EvaluateException {
        Object result;
        try{
            result = ((JavascriptExecutor) driver).executeAsyncScript(EVALUATE_PREFIX + expression.strip() + EVALUATE_SUFFIX);
        }catch (WebDriverException e){
            //A syntax error in the expression fails the whole script, as it fails Playwright's eval.
            throw new EvaluateException(e.getMessage(), e);
        }
        if(!(result instanceof Map<?, ?> map)){
            throw new EvaluateException("Unexpected evaluation result: " + result);
        }
        if(map.containsKey("err")){
            throw new EvaluateException(String.valueOf(map.get("err")));
        }
        Object json = map.get("ok");
        return PyRepr.fromPlaywright(json == null? null : json.toString());
    }

    @Override
    public String content() {
        return String.valueOf(((JavascriptExecutor) driver).executeScript(CONTENT_JS));
    }
}
