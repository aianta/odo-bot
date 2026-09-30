package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.semanticflow.model.Observation;
import ca.ualberta.odobot.semanticflow.model.Screenshot;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Makes the observation of each uncharted step, as OSWorld does: execute the action, wait, take a screenshot.
 *
 * <p>Observing starts once a step has been sent to OdoX. Every event OdoX sends restarts a quiet period; when the page has been quiet
 * for {@link #quietPeriod} the screenshot is requested, and the resulting {@link Observation} (trigger
 * {@link Observation.Trigger#UNCHARTED_ACTION}) goes to the uncharted agent. So every step yields exactly one screenshot, taken after
 * the step, once the page has settled, whatever events the step produced, including none. A page that never goes quiet is
 * screenshotted after {@link #maxWait}, and a {@code wait} action is never observed before its duration has elapsed.</p>
 *
 * <p>Calls must come from a single Vert.x context; the timers it sets run there too.</p>
 */
public class UnchartedStepObserver {

    private static final Logger log = LoggerFactory.getLogger(UnchartedStepObserver.class);

    /**
     * The duration OdoX waits for a {@code wait} action without one, UNCHARTED_DEFAULT_WAIT_SECONDS in guidance.js.
     */
    private static final double DEFAULT_WAIT_SECONDS = 2.0;

    /**
     * How long, in milliseconds, the page must go without an event before the screenshot is taken.
     */
    public long quietPeriod = 1500;

    /**
     * How long, in milliseconds, after the step was sent the screenshot is taken even if the page is still busy.
     */
    public long maxWait = 15000;

    /**
     * How long, in milliseconds, to wait for OdoX's reply to a screenshot request before asking again.
     */
    public long replyTimeout = 10000;

    /**
     * How many times a screenshot is requested before giving up on the step.
     */
    public int maxScreenshotRequests = 3;

    private final Vertx vertx;
    private final Runnable requestScreenshot;
    private final Consumer<Observation> onObservation;

    /**
     * The step being observed, as sent to OdoX, or null.
     */
    private JsonObject step = null;
    private long sentAt;
    private long notBefore;
    private long timer = -1;
    private int screenshotRequests = 0;

    /**
     * @param requestScreenshot sends a GET_SCREENSHOT request to OdoX. Its reply must be passed to {@link #onScreenshot}.
     * @param onObservation receives the observation of each step.
     */
    public UnchartedStepObserver(Vertx vertx, Runnable requestScreenshot, Consumer<Observation> onObservation){
        this.vertx = vertx;
        this.requestScreenshot = requestScreenshot;
        this.onObservation = onObservation;
    }

    /**
     * Starts observing a step that has just been sent to OdoX. A step still being observed is abandoned.
     * @param step the step instruction as sent, with its action under {@code unchartedAction}.
     */
    public void observe(JsonObject step){
        if(this.step != null){
            log.warn("Uncharted step {} was sent before step {} was observed, abandoning the observation of step {}.",
                    step.getValue("step"), this.step.getValue("step"), this.step.getValue("step"));
        }
        this.step = step;
        this.sentAt = System.currentTimeMillis();
        this.notBefore = sentAt + waitMillis(step.getJsonObject("unchartedAction"));
        this.screenshotRequests = 0;
        schedule(Math.max(quietPeriod, notBefore - sentAt));
    }

    /**
     * An event arrived from OdoX: the page is not quiet yet.
     */
    public void onEvent(){
        if(step == null || screenshotRequests > 0){
            return;
        }
        long now = System.currentTimeMillis();
        long deadline = sentAt + maxWait;
        schedule(Math.max(0, Math.min(Math.max(quietPeriod, notBefore - now), deadline - now)));
    }

    /**
     * OdoX's reply to a screenshot request.
     * @param base64Screenshot the screenshot, or null.
     * @param userLocation the url of the active tab, or null.
     */
    public void onScreenshot(String base64Screenshot, String userLocation){
        if(step == null || screenshotRequests == 0){
            log.warn("Got a screenshot that no uncharted step is waiting for, ignoring it.");
            return;
        }
        cancelTimer();
        Observation observation = new Observation(Observation.Trigger.UNCHARTED_ACTION, step.getJsonObject("unchartedAction"),
                userLocation, Screenshot.fromBase64(base64Screenshot), System.currentTimeMillis());
        log.info("Observed uncharted step {} {} ms after it was sent.", step.getValue("step"), observation.timestamp() - sentAt);
        step = null;
        screenshotRequests = 0;
        onObservation.accept(observation);
    }

    /**
     * Stops observing, e.g. when the task ends.
     */
    public void stop(){
        cancelTimer();
        step = null;
        screenshotRequests = 0;
    }

    private void schedule(long delay){
        cancelTimer();
        timer = vertx.setTimer(Math.max(1, delay), id->{
            timer = -1;
            onTimer();
        });
    }

    private void onTimer(){
        if(step == null){
            return;
        }
        long now = System.currentTimeMillis();
        if(screenshotRequests == 0 && now < notBefore){
            schedule(notBefore - now);
            return;
        }
        if(screenshotRequests >= maxScreenshotRequests){
            log.error("OdoX did not answer {} screenshot requests for uncharted step {}, giving up on observing it.",
                    screenshotRequests, step.getValue("step"));
            stop();
            return;
        }
        if(screenshotRequests > 0){
            log.warn("No reply to the screenshot request for uncharted step {}, asking again.", step.getValue("step"));
        }
        screenshotRequests++;
        requestScreenshot.run();
        schedule(replyTimeout);
    }

    private void cancelTimer(){
        if(timer != -1){
            vertx.cancelTimer(timer);
            timer = -1;
        }
    }

    /**
     * How long a wait action makes OdoX wait, in milliseconds, or 0 for other actions.
     */
    static long waitMillis(JsonObject action){
        if(action == null || !"wait".equals(action.getString("action"))){
            return 0;
        }
        Double seconds = action.getDouble("seconds");
        return Math.round(1000 * Math.max(0, seconds != null ? seconds : DEFAULT_WAIT_SECONDS));
    }
}
