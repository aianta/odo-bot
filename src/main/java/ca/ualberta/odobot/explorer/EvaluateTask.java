package ca.ualberta.odobot.explorer;

import ca.ualberta.odobot.guidance.OdoClient;
import ca.ualberta.odobot.guidance.OnlineEventProcessor;
import ca.ualberta.odobot.guidance.WebSocketConnection;
import ca.ualberta.odobot.guidance.execution.ExecutionParameter;
import ca.ualberta.odobot.guidance.execution.ExecutionRequest;
import ca.ualberta.odobot.taskplanner.TaskPlannerService;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.firefox.ProfilesIni;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.UUID;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.explorer.ExploreTask.ODOSIGHT_OPTIONS_LOGUI_SERVER_PASSWORD_FIELD_ID;
import static ca.ualberta.odobot.explorer.ExploreTask.ODOSIGHT_OPTIONS_LOGUI_SERVER_USERNAME_FIELD_ID;
import static ca.ualberta.odobot.explorer.WebDriverUtils.*;

public class EvaluateTask implements Runnable{

    private static final Logger log = LoggerFactory.getLogger(EvaluateTask.class);
    private static final String ADDON_ID = "odosight@ualberta.ca";

    Agent agent;
    UUID dynamicAddonId = UUID.randomUUID();

    String webAppTabHandle;
    String odoXControlsTabHandle;
    String odoXControlsUrl;
    String odoXOptionsUrl;
    JsonObject config;
    JsonArray tasks;
    JsonObject task;

    FirefoxDriver driver;
    OdoClient odoXClient;

    Promise<Void> promise;

    TaskPlannerService taskPlannerService;

    public EvaluateTask(JsonObject config, JsonObject task, Promise<Void> promise, TaskPlannerService taskPlannerService, Agent agent){
        this.agent = agent;
        this.taskPlannerService = taskPlannerService;
        this.config = config;
        this.promise = promise;
        this.task = task;

        this.odoXControlsUrl = "moz-extension://"+dynamicAddonId.toString()+"/popup/bot/bot.html";
        this.odoXOptionsUrl = "moz-extension://"+dynamicAddonId.toString()+"/options/options.html";
    }

    @Override
    public void run() {

        FirefoxOptions options = new FirefoxOptions();
        //options.addArguments("--headless");

        options.setProfile(buildProfile());

        driver = new FirefoxDriver(options);
        driver.installExtension(Path.of(config.getString(EvaluationTaskRequestFields.ODOSIGHT_PATH.field)),true);

        //Load Evaluation Canvas State

        //Setup OdoX
        setupOdoX();

        //Navigate to Canvas Login

        //At this stage OdoX should be connected to the guidance vertical.
        assert WebSocketConnection.clientMap.size() == 1;

        log.info("OdoX connected to Guidance Subsystem");

        odoXClient = WebSocketConnection.clientMap.entrySet().iterator().next().getValue();

        startTask(task);


    }

    public void startTask(JsonObject task){

        log.info("Starting task {}", task.getString("_evalId"));

        taskToExecutionRequest(task).onSuccess(executionRequest->{
            Promise<Void> evaluationPromise = Promise.promise();
            evaluationPromise.future().onComplete((done)->this.taskComplete());

            odoXClient.getRequestManager().setEvaluationComplete(evaluationPromise);
            odoXClient.getRequestManager().setEvalId(task.getString("_evalId")); //Set the evaluationId for this execution.
            odoXClient.getRequestManager().addNewRequest(executionRequest);
        });


    }

    public void taskComplete(){
        cleanUp();
        promise.complete();
    }

    public void cleanUp(){

        //Close the browser
        driver.close();
        driver.quit();
        driver = null;

        //Clear client map
        WebSocketConnection.clientMap.clear();

    }



    private Future<ExecutionRequest> taskToExecutionRequest(JsonObject task){
        ExecutionRequest executionRequest = new ExecutionRequest();

        if(agent == Agent.ODO_BOT_NL){
            return taskPlannerService.taskQueryConstruction(task)
                    .compose(definedTask->{
                        log.info("Got task definition from task query construction:\n{}", definedTask.encodePrettily());
                        saveTaskQueryConstructionResult("./%s/%s-task-query-construction-result.json".formatted("execution_events", definedTask.getString("_evalId")).replaceAll("\\|","-"), definedTask);

                        executionRequest.setId(UUID.fromString(definedTask.getString("id")));
                        executionRequest.setUserLocation(definedTask.getString("userLocation"));
                        executionRequest.setType(ExecutionRequest.Type.NL);

                        JsonArray targets = definedTask.getJsonArray("targets");
                        executionRequest.setTargets(targets.stream()
                                .map(o->(String)o)
                                .collect(Collectors.toSet())
                        );

                        JsonArray parameters = definedTask.getJsonArray("parameters");
                        executionRequest.setParameters(parameters.stream()
                                .map(o->(JsonObject)o)
                                .map(ExecutionParameter::fromJson)
                                .collect(Collectors.toList())
                        );

                        return Future.succeededFuture(executionRequest);
                    });
        }

        if(agent == Agent.ODO_BOT){
            executionRequest.setId(UUID.fromString(task.getString("id")));
            executionRequest.setTarget(UUID.fromString(task.getString("target")));
            executionRequest.setUserLocation(task.getString("userLocation"));
            executionRequest.setType(ExecutionRequest.Type.PREDEFINED);

            JsonArray parameters = task.getJsonArray("parameters");
            executionRequest.setParameters(parameters.stream()
                    .map(o->(JsonObject)o)
                    .map(ExecutionParameter::fromJson)
                    .collect(Collectors.toList())
            );

            return Future.succeededFuture(executionRequest);
        }

        log.error("Unknown or unsupported agent type!");
        return Future.failedFuture("Unknown or unsupported agent type!");
    }

    private void saveTaskQueryConstructionResult(String filename, JsonObject result){
        File fout = new File(filename);
        try(FileWriter fw = new FileWriter(fout);
            BufferedWriter bw = new BufferedWriter(fw);
        ){

            bw.write(result.encodePrettily());
            bw.flush();

        } catch (IOException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

    }

    /**
     * Start up OdoX and connect to Guidance service.
     */
    private void setupOdoX(){

        /*
         * First we need to provide a valid LogUI config, so we have to set the username and
         * password for LogUI even though we won't be sending events to the LogUI server during
         * evaluation.
         */

        /**
         * This method is the first thing that is called after the browser opens, let's give OdoX a chance to initialize before we call its option page.
         * If we don't do this, the default option values that the extension setup when initializing won't be populated in localStorage which means
         * they won't show up on the options page, which means we'll overwrite option values with blanks, which we do not want.
         */
        explicitlyWait(driver, 3);

        //Load the OdoX options page
        driver.get(odoXOptionsUrl);

        //Get the username input field, clear any existing value, then set it to the value specified in the request config
        WebElement usernameInput = driver.findElement(By.id(ODOSIGHT_OPTIONS_LOGUI_SERVER_USERNAME_FIELD_ID));
        usernameInput.clear();
        usernameInput.sendKeys(config.getString(EvaluationTaskRequestFields.ODOX_OPTIONS_LOGUI_USERNAME.field));

        //Get the password input field, clear any existing value, then set it to the value specified in the request config
        WebElement passwordInput = driver.findElement(By.id(ODOSIGHT_OPTIONS_LOGUI_SERVER_PASSWORD_FIELD_ID));
        passwordInput.clear();
        passwordInput.sendKeys(config.getString(EvaluationTaskRequestFields.ODOX_OPTIONS_LOGUI_PASSWORD.field));

        //Update/save the options
        WebElement submitButton = findElement(driver, By.tagName("button"));
        click(driver, submitButton);

        //Load the Target application
        driver.get(config.getString(EvaluationTaskRequestFields.WEB_APP_URL.field));

        //Save the handle to the current tab displaying the target application.
        webAppTabHandle = driver.getWindowHandle();

        /**
         * Next we're going to open up the odoX controls to get into bot mode.
         * This should establish the connection with the OdoBot server provided the guidance subsystem is running.
         */

        //Create a new tab and switch to it.
        driver.switchTo().newWindow(WindowType.TAB);

        //Save the handle to the OdoX controls tab
        odoXControlsTabHandle = driver.getWindowHandle();

        //Open the OdoXControls
        driver.get(odoXControlsUrl);

        //Then switch back to the target web app tab
        driver.switchTo().window(webAppTabHandle);

//        //Wait for OdoX to connect to the OdoBot server.
//        explicitlyWait(driver, 3);
//
//        //Refresh the web app page
//        driver.get(config.getString(EvaluationTaskRequestFields.WEB_APP_URL.field));

        //Wait for OdoX websockets to stabilize
        explicitlyWait(driver, 5);

    }

    /**
     * To configure the odo sight extension once it is installed, we have to get access to the extensions
     * options.html page. This will be located at moz-extension://[some UUID goes here]/options.html.
     *
     * To access the options page we need to find the addon's UUID, which cannot be done at runtime,
     * and must therefore be seeded through a profile before the webdriver starts.
     *
     * This method constructs an appropriate profile using {@link #dynamicAddonId}.
     *
     * NOTE: This dynamic addon id is NOT the same as {@link #ADDON_ID}.
     *
     * For more details see: https://airtower.wordpress.com/2020/07/19/configure-a-firefox-web-extension-from-selenium/
     * or https://archive.ph/Iv1QR if the first link is dead.
     *
     * And Selenium documentation on Firefox Profiles here: https://www.selenium.dev/documentation/webdriver/browsers/firefox/#profiles
     *
     * @return
     */
    private FirefoxProfile buildProfile(){
        JsonObject addonIdPreference = new JsonObject()
                .put(ADDON_ID, dynamicAddonId.toString());

        /**
         * Disable any sort of features that introduce random modal/popups.
         *
         * For example browser.newtabpage.activity-stream.asrouter.providers.onboarding, if enabled, displays a modal on startup
         * where the user is prompted to make firefox the default opener of weblinks on their system. This would appear when
         * the selenium controlled browser starts up and cause selenium to fail as it tried to setup odo-sight.
         */
        ProfilesIni allProfiles = new ProfilesIni();
        FirefoxProfile profile = allProfiles.getProfile("Selenium");
        profile.setAcceptUntrustedCertificates(true); //LogUI Server is likely running locally over a self-signed cert.
        profile.setPreference("extensions.webextensions.uuids", addonIdPreference.encode());
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

