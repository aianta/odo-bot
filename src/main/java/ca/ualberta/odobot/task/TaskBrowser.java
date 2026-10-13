package ca.ualberta.odobot.task;

import org.openqa.selenium.*;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.firefox.HasExtensions;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.core.json.JsonObject;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
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
     * An MV2 add-on that removes Content Security Policy headers from every response (source in csp-strip/). Without it,
     * sites that enforce a CSP, such as the WebArena Forum, block OdoX's WebSocket to OdoBot. OdoX is MV3, and Firefox
     * ignores CSP header changes from MV3 extensions, so this can't be done inside OdoX. It is installed into every
     * task browser before OdoX, and only affects responses that arrive after it is installed.
     */
    private static final Path CSP_STRIP_PATH = Path.of("csp-strip.xpi");

    /**
     * How often setup is tried again when OdoX's options page is missing an element, e.g. because the extension had
     * not initialized yet.
     */
    private static final int MAX_SETUP_ATTEMPTS = 5;

    private final String evalId;
    private final UUID addonId;
    private final TaskEnvironment environment;
    private final String odoSightPath;
    private final boolean headless;

    private final String odoXControlsUrl;
    private final String odoXOptionsUrl;

    private volatile WebDriver driver;

    /**
     * @param addonId OdoX's extension instance id, which is also its clientId on the sockets.
     * @param odoSightPath the OdoX xpi.
     */
    public TaskBrowser(String evalId, UUID addonId, TaskEnvironment environment, String odoSightPath, boolean headless){
        this.evalId = evalId;
        this.addonId = addonId;
        this.environment = environment;
        this.odoSightPath = odoSightPath;
        this.headless = headless;
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
     * controls. OdoX then connects its sockets.
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
            String cspStripId = ((HasExtensions) augmentedDriver).installExtension(CSP_STRIP_PATH, true);
            log.info("Installed CSP stripping extension with id {}", cspStripId);
            String extensionId = ((HasExtensions) augmentedDriver).installExtension(Paths.get(odoSightPath));
            log.info("Installed extension with id {}", extensionId);
        } else {
            driver = new FirefoxDriver(options);
            ((FirefoxDriver) driver).installExtension(CSP_STRIP_PATH, true);
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
         *
         * This is the first thing that happens after the browser opens, so give OdoX a chance to initialize before
         * loading its options page. Otherwise the defaults it sets up on initialization aren't in localStorage yet,
         * don't show up on the options page, and would be overwritten with blanks.
         */
        explicitlyWait(driver, 3);
        driver.get(odoXOptionsUrl);

        setField(ODOSIGHT_OPTIONS_GUIDANCE_SERVICE_HOST_FIELD_ID, guidanceHost);
        //The hosts whose network events OdoX captures, e.g. "http://172.23.0.2,http://172.23.0.6:8023", or "*" for every host.
        setField(ODOSIGHT_OPTIONS_TARGET_HOST_FIELD_ID, String.join(",", environment.targetHosts()));
        setField(ODOSIGHT_OPTIONS_LOGUI_SERVER_HOST_FIELD_ID, environment.logUiHost());
        setField(ODOSIGHT_OPTIONS_LOGUI_SERVER_USERNAME_FIELD_ID, environment.logUiUsername());
        setField(ODOSIGHT_OPTIONS_LOGUI_SERVER_PASSWORD_FIELD_ID, environment.logUiPassword());

        //Update/save the options
        WebElement submitButton = findElement(driver, By.tagName("button"));
        click(driver, submitButton);

        //Load the target application, and remember its tab.
        driver.get(environment.startUrl());
        String webAppTabHandle = driver.getWindowHandle();

        //Open OdoX's controls in a new tab. This connects OdoX to OdoBot.
        driver.switchTo().newWindow(WindowType.TAB);
        driver.get(odoXControlsUrl);

        //Then switch back to the target web app tab.
        driver.switchTo().window(webAppTabHandle);
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
