package ca.ualberta.odobot.task;

import ca.ualberta.odobot.guidance.TaskTiming;
import ca.ualberta.odobot.task.webarena.SessionAuthenticator;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.*;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.firefox.HasExtensions;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static ca.ualberta.odobot.explorer.ExploreTask.*;
import static ca.ualberta.odobot.explorer.WebDriverUtils.*;

/**
 * The Firefox of one task, with OdoX installed and pointed at the task's own listener. Selenium blocks, so every
 * method here runs on a worker thread.
 */
public class TaskBrowser {

    private static final Logger log = LoggerFactory.getLogger(TaskBrowser.class);
    private static final String ADDON_ID = "odosight@ualberta.ca";

    /**
     * How often setup is tried again when OdoX's options page is missing an element, e.g. because the extension had
     * not initialized yet.
     */
    private static final int MAX_SETUP_ATTEMPTS = 5;

    /**
     * How often OdoX's options are entered again when OdoX did not store what was entered, see {@link #configureOdoX}.
     */
    private static final int MAX_OPTIONS_ATTEMPTS = 3;

    /**
     * How long OdoX's options page may take to fill itself in, or to store a save.
     */
    private static final Duration OPTIONS_PAGE_TIMEOUT = Duration.ofSeconds(30);

    private final String evalId;
    private final UUID addonId;
    private final TaskEnvironment environment;
    private final String odoSightPath;
    private final boolean headless;
    private final TaskTiming timing;

    /**
     * How the sites were logged in to before the agent started, or null if setup hasn't got that far.
     */
    private volatile JsonObject authReport;

    private final String odoXControlsUrl;
    private final String odoXOptionsUrl;

    private volatile WebDriver driver;

    /**
     * The tab of the web app the agent works in, as opposed to OdoX's controls tab.
     */
    private volatile String appTabHandle;

    /**
     * @param addonId OdoX's extension instance id, which is also its clientId on its socket.
     * @param odoSightPath the OdoX xpi.
     * @param timing marks the time spent logging in to the task's sites.
     */
    public TaskBrowser(String evalId, UUID addonId, TaskEnvironment environment, String odoSightPath, boolean headless,
                       TaskTiming timing){
        this.evalId = evalId;
        this.addonId = addonId;
        this.environment = environment;
        this.odoSightPath = odoSightPath;
        this.headless = headless;
        this.timing = timing;
        this.odoXControlsUrl = "moz-extension://" + addonId + "/popup/bot/bot.html";
        this.odoXOptionsUrl = "moz-extension://" + addonId + "/options/options.html";
    }

    /**
     * @return the driver, or null if the browser isn't open. Evaluators use it while the browser is still open.
     */
    public WebDriver driver(){
        return driver;
    }

    /**
     * Start Firefox, configure OdoX to reach OdoBot at {@code guidanceHost}, and open the start page and OdoX's
     * controls. OdoX then connects its socket.
     */
    public void open(String guidanceHost) throws MalformedURLException {
        for(int attempt = 1; ; attempt++){
            try{
                start();
                setupOdoX(guidanceHost);
                return;
            }catch (NoSuchElementException e){
                close();
                if(attempt >= MAX_SETUP_ATTEMPTS){
                    throw e;
                }
                log.warn("Task {}: OdoX's options page was not ready ({}), setting the browser up again.", evalId, e.getMessage());
            }
        }
    }

    private void start() throws MalformedURLException {
        FirefoxOptions options = new FirefoxOptions();
        if (headless) {
            log.info("Running in headless mode");
            options.addArguments("--headless");
        }

        /*
         * IMPORTANT: the browser this driver connects to must be started with MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1 in its
         * environment.
         *
         * Since Firefox 152, WebDriver refuses to navigate to any URL whose scheme is not one of
         * ["blob", "file", "http", "https"], and refuses to evaluate scripts against privileged browsing contexts. See
         * isWebdriverSafeNavigationURL() in BrowsingContextUtils.sys.mjs and navigateTo() in marionette/driver.sys.mjs.
         * Driving OdoX needs both, since its options and bot control pages are moz-extension:// URLs. Without it,
         * setupOdoX() fails:
         *   Navigation to "moz-extension://[...]/options/options.html" is not allowed in this context
         *
         * Both restrictions are lifted when RemoteAgent.allowSystemAccess is set, which the above environment variable
         * does. It cannot be set from here: geckodriver rejects both "--remote-allow-system-access" and the environment
         * variable itself when they arrive via capabilities ("... can't be set via capabilities"). So for the docker
         * grid it has to be passed to the container:
         *   docker run -e MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1 ... selenium/standalone-firefox:dev
         * and for a local driver, set it in OdoBot's own environment before starting.
         */
        options.addPreference("network.dns.disableIPv6", true);
        FirefoxProfile profile = buildProfile();
        profile.addExtension(new File(odoSightPath));

        options.setProfile(buildProfile());

        if (environment.gridUrl() != null) {
            driver = new RemoteWebDriver(new URL(environment.gridUrl()), options);
            WebDriver augmentedDriver = new Augmenter().augment(driver);
            String extensionId = ((HasExtensions) augmentedDriver).installExtension(Paths.get(odoSightPath));
            log.info("Installed extension with id {}", extensionId);
        } else {
            driver = new FirefoxDriver(options);
            ((FirefoxDriver) driver).installExtension(Path.of(odoSightPath), true);
        }
    }

    /**
     * Fill in OdoX's options, then open the start page and OdoX's controls, which connect OdoX to OdoBot.
     */
    private void setupOdoX(String guidanceHost){

        /*
         * OdoX needs a valid LogUI config, so the LogUI username and password are set even though no events are sent
         * to the LogUI server during evaluation.
         */
        Map<String, String> options = new LinkedHashMap<>();
        options.put(ODOSIGHT_OPTIONS_GUIDANCE_SERVICE_HOST_FIELD_ID, guidanceHost);
        //The hosts whose network events OdoX captures, e.g. "http://172.23.0.2,http://172.23.0.6:8023", or "*" for every host.
        options.put(ODOSIGHT_OPTIONS_TARGET_HOST_FIELD_ID, String.join(",", environment.targetHosts()));
        options.put(ODOSIGHT_OPTIONS_LOGUI_SERVER_HOST_FIELD_ID, environment.logUiHost());
        options.put(ODOSIGHT_OPTIONS_LOGUI_SERVER_USERNAME_FIELD_ID, environment.logUiUsername());
        options.put(ODOSIGHT_OPTIONS_LOGUI_SERVER_PASSWORD_FIELD_ID, environment.logUiPassword());
        configureOdoX(options);

        //Start logged in to the task's sites, as WebArena does, before the start page loads and before OdoX connects.
        logIn();

        //Load the target application, and remember its tab.
        driver.get(environment.startUrl());
        String webAppTabHandle = driver.getWindowHandle();
        appTabHandle = webAppTabHandle;

        //Open OdoX's controls in a new tab. This connects OdoX to OdoBot.
        driver.switchTo().newWindow(WindowType.TAB);
        driver.get(odoXControlsUrl);

        //Then switch back to the target web app tab.
        driver.switchTo().window(webAppTabHandle);
    }

    /**
     * Enter OdoX's options, save them, and check that OdoX stored them.
     *
     * <p>OdoX's options page fills its fields from storage asynchronously, and before OdoX has initialized its state
     * that state may still be replaced by the defaults. Typing into the page too early can therefore leave OdoX with the
     * wrong options, e.g. a guidance host of "localhost:7080172.23.0.2:40000", after which OdoX never reaches OdoBot.
     * So the options are typed only once the page reports it has filled itself in (data-loaded), and are read back from
     * a freshly loaded page after the save, which shows what OdoX stored. If they don't match, they are entered again.</p>
     *
     * @param options the value of each options field, by field id.
     * @throws IllegalStateException if OdoX did not store the options after {@link #MAX_OPTIONS_ATTEMPTS} attempts.
     */
    private void configureOdoX(Map<String, String> options){
        String problem = null;
        for(int attempt = 1; attempt <= MAX_OPTIONS_ATTEMPTS; attempt++){
            try{
                openOdoXOptions();
                options.forEach(this::setField);
                click(driver, findElement(driver, By.tagName("button")));
                new WebDriverWait(driver, OPTIONS_PAGE_TIMEOUT)
                        .until(d->"true".equals(d.findElement(By.tagName("form")).getAttribute("data-saved")));

                //A freshly loaded options page shows what OdoX stored.
                openOdoXOptions();
                Map<String, String> wrong = new LinkedHashMap<>();
                options.forEach((field, expected)->{
                    String stored = driver.findElement(By.id(field)).getAttribute("value");
                    if(!(expected == null? "" : expected).equals(stored)){
                        wrong.put(field, stored);
                    }
                });
                if(wrong.isEmpty()){
                    return;
                }
                problem = "OdoX stored " + wrong;
            }catch (TimeoutException e){
                problem = "OdoX's options page did not finish loading or saving within " + OPTIONS_PAGE_TIMEOUT.toSeconds() + " s";
            }
            log.warn("Task {}: setting OdoX's options, attempt {} of {}: {}", evalId, attempt, MAX_OPTIONS_ATTEMPTS, problem);
        }
        throw new IllegalStateException("Could not set OdoX's options: " + problem);
    }

    /**
     * Open OdoX's options page, and wait until it has filled itself in from OdoX's state.
     */
    private void openOdoXOptions(){
        driver.get(odoXOptionsUrl);
        new WebDriverWait(driver, OPTIONS_PAGE_TIMEOUT)
                .until(d->"true".equals(d.findElement(By.tagName("form")).getAttribute("data-loaded")));
    }

    /**
     * Log in to the sites the task starts logged in to, see {@link SessionAuthenticator}. Nothing to do in prompt mode.
     *
     * @throws SessionAuthenticator.AuthFailedException if a site could not be logged in to.
     */
    private void logIn(){
        if(environment.auth().sitesToLogIn().isEmpty()){
            authReport = environment.auth().toJson().put("sites", new JsonObject());
            return;
        }
        timing.markAuthStart();
        try{
            authReport = SessionAuthenticator.authenticate(driver, environment.auth());
        }catch (SessionAuthenticator.AuthFailedException e){
            authReport = e.report;
            throw e;
        }finally {
            timing.markAuthEnd();
        }
        log.info("Task {}: logged in: {}", evalId, authReport.encode());
    }

    /**
     * @return how the sites were logged in to, {@code {mode, sites: {site: {status, method, ms}}}}, or null.
     */
    public JsonObject authReport(){
        return authReport;
    }

    /**
     * Switch the driver to the agent's tab, so that evaluators read the page the agent ended on. If that tab was
     * closed, the most recently opened tab that isn't OdoX's is used.
     *
     * @return the tab's handle.
     * @throws IllegalStateException if the browser isn't open or has no such tab.
     */
    public String focusAppTab(){
        WebDriver open = driver;
        if(open == null){
            throw new IllegalStateException("The browser is not open.");
        }
        java.util.Set<String> handles = open.getWindowHandles();
        if(appTabHandle != null && handles.contains(appTabHandle)){
            open.switchTo().window(appTabHandle);
            return appTabHandle;
        }
        String fallback = null;
        for(String handle: handles){
            open.switchTo().window(handle);
            if(!open.getCurrentUrl().startsWith("moz-extension://")){
                fallback = handle;
            }
        }
        if(fallback == null){
            throw new IllegalStateException("The agent's tab is gone and no other web page is open.");
        }
        open.switchTo().window(fallback);
        return fallback;
    }

    private void setField(String id, String value){
        WebElement input = driver.findElement(By.id(id));
        input.clear();
        input.sendKeys(value == null? "" : value);
    }

    /**
     * Quit the browser, if it is open.
     */
    public void close(){
        WebDriver open = driver;
        driver = null;
        if(open == null){
            return;
        }
        try{
            open.close();
            open.quit();
        }catch (NoSuchSessionException e){
            //Already gone.
        }catch (Exception e){
            log.warn("Task {}: could not close the browser: {}", evalId, e.getMessage());
        }
    }

    /**
     * To configure OdoX once it is installed, setup needs its options page at moz-extension://[some UUID]/options.html.
     * That UUID can't be found at runtime, so it is seeded through the profile before the driver starts. It is not
     * the same as {@link #ADDON_ID}.
     *
     * See https://airtower.wordpress.com/2020/07/19/configure-a-firefox-web-extension-from-selenium/ (or
     * https://archive.ph/Iv1QR) and https://www.selenium.dev/documentation/webdriver/browsers/firefox/#profiles
     */
    private FirefoxProfile buildProfile(){
        JsonObject addonIdPreference = new JsonObject()
                .put(ADDON_ID, addonId.toString());

        /*
         * Disable any sort of features that introduce random modal/popups.
         *
         * For example browser.newtabpage.activity-stream.asrouter.providers.onboarding, if enabled, displays a modal on
         * startup where the user is prompted to make firefox the default opener of weblinks on their system. This would
         * appear when the selenium controlled browser starts up and cause selenium to fail as it tried to setup OdoX.
         */
        FirefoxProfile profile = new FirefoxProfile();
        profile.setAcceptUntrustedCertificates(true); //OdoBot's and LogUI's servers use self-signed certs.
        profile.setPreference("extensions.webextensions.uuids", addonIdPreference.encode());
        profile.setPreference("xpinstall.signatures.required", false);
        profile.setPreference("remote.active-protocols", 2);
        profile.setPreference("browser.newtabpage.activity-stream.asrouter.userprefs.cfr.features", false);
        profile.setPreference("browser.aboutwelcome.enabled", false);
        profile.setPreference("browser.messaging-system.whatsNewPanel.enabled", false);
        profile.setPreference("browser.migrate.content-modal.import-all.enabled", false);
        profile.setPreference("messaging-system.askForFeedback", false);
        profile.setPreference("messaging-system.rsexperimentloader.enabled", false);
        profile.setPreference("services.sync.prefs.sync.browser.firefox-view.feature-tour", false);
        profile.setPreference("services.sync.prefs.sync.browser.newtabpage.activity-stream.asrouter.userprefs.cfr.addons", false);
        profile.setPreference("services.sync.prefs.sync.browser.newtabpage.activity-stream.asrouter.userprefs.cfr.features", false);
        profile.setPreference("browser.newtabpage.activity-stream.asrouter.userprefs.cfr.addons", false);
        profile.setPreference("browser.newtabpage.activity-stream.asrouter.useRemoteL10n", false);
        profile.setPreference("browser.newtabpage.activity-stream.asrouter.providers.onboarding", "{\"id\":\"onboarding\",\"type\":\"local\",\"localProvider\":\"OnboardingMessageProvider\",\"enabled\":false,\"exclude\":[]}");

        return profile;
    }
}
