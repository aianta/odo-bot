package ca.ualberta.odobot.task.webarena;

/**
 * The browser page WebArena's evaluators read: the Playwright {@code Page} of the original, the agent's tab here.
 * Every method blocks, so evaluators run on a worker thread.
 */
public interface WebArenaPage {

    /**
     * @return the page's current URL, as Playwright's {@code page.url}.
     */
    String url();

    /**
     * Navigate and wait for the page to load, as Playwright's {@code page.goto(url)}. Evaluators settle afterwards.
     */
    void goTo(String url);

    /**
     * Evaluate a JS expression as Playwright's {@code page.evaluate(expression)} does: the expression is evaluated, a
     * function result is called, a promise is awaited, and the result is serialized the way Playwright 1.32 does.
     *
     * @return the Python value Playwright's Python client would return, see
     * {@link ca.ualberta.odobot.task.webarena.py.PyRepr}.
     * @throws EvaluateException if the expression throws, or can't be evaluated.
     */
    Object evaluate(String expression) throws EvaluateException;

    /**
     * @return the serialized DOM, as Playwright's {@code page.content()}.
     */
    String content();

    class EvaluateException extends Exception {
        public EvaluateException(String message){
            super(message);
        }

        public EvaluateException(String message, Throwable cause){
            super(message, cause);
        }
    }
}
