package ca.ualberta.odobot.explorer;

import ca.ualberta.odobot.explorer.canvas.operations.*;
import ca.ualberta.odobot.explorer.canvas.resources.*;
import ca.ualberta.odobot.explorer.canvas.resources.Module;
import ca.ualberta.odobot.explorer.model.Operation;
import ca.ualberta.odobot.explorer.model.OperationFailure;
import ca.ualberta.odobot.explorer.model.OperationFailures;
import ca.ualberta.odobot.explorer.model.ToDo;

import edu.stanford.nlp.time.Options;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import org.openqa.selenium.*;
import org.openqa.selenium.Point;
import org.openqa.selenium.firefox.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.explorer.WebDriverUtils.*;

public class ExploreTask implements Runnable{

    private static final Logger log = LoggerFactory.getLogger(ExploreTask.class);

    private final static Random random = new Random();

    /**
     * As specified in OdoSight's manifest.json.
     *
     * For more details about addon IDs see:
     * https://extensionworkshop.com/documentation/develop/extensions-and-the-add-on-id/
     *
     * For more details on how they're specified in manifest.json, see:
     * https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/browser_specific_settings
     */
    private static final String ADDON_ID = "odosight@ualberta.ca";
    private static final String ODOSIGHT_OPTIONS_LOGUI_SERVER_USERNAME_FIELD_ID = "logui-server-username";
    private static final String ODOSIGHT_OPTIONS_LOGUI_SERVER_PASSWORD_FIELD_ID = "logui-server-password";
    private static final String ODOSIGHT_CONTROLS_EDIT_FLIGHT_BUTTON_ID = "edit_flight_btn";
    private static final String ODOSIGHT_CONTROLS_NEW_FLIGHT_BUTTON_ID = "new-flight-btn";
    private static final String ODOSIGHT_CONTROLS_NEW_FLIGHT_NAME_INPUT_ID = "new-flight-name";
    private static final String ODOSIGHT_CONTROLS_SESSION_ID_LABEL_ID = "session_id";
    private static final String ODOSIGHT_CONTROLS_START_RECORDING_BUTTON_ID = "start-btn";
    private static final String ODOSIGHT_CONTROLS_STOP_RECORDING_BUTTON_ID = "stop-btn";
    private static final String ODOSIGHT_CONTROLS_SCRAPE_MONGO_BUTTON_ID = "scrape_mongo_btn";

    private static final String ODOSIGHT_CONTROLS_SCRAPE_ES_INDEX_INPUT_ID = "scrape-index";

    JsonObject config;
    UUID dynamicAddonId = UUID.randomUUID();
    String odoSightOptionsUrl;
    String odoSightControlsUrl;
    String odoSightControlsTabHandle;
    String webAppTabHandle;
    FirefoxDriver driver;
    int recordingCount = 0;
    String currentFlight;

    /**
     * Json encoded manifest of all cases to execute. The primaryToDo should be reconstructed from this data.
     */
    List<JsonObject> inputManifest;

    Set<UUID> completedOperations = new HashSet<>();

    ToDo primaryToDo = new ToDo();

    Operation loginOperation;
    Operation logoutOperation;

    List<CourseResources> resourcesList;


    public ExploreTask(JsonObject config){
        this.config = config;

        /**
         * See documentation for{@link #buildProfile()}
         *
         * Also, in theory these URL paths could break if the extension structure is updated.
         */
        this.odoSightOptionsUrl = "moz-extension://"+dynamicAddonId.toString()+"/options/options.html";
        this.odoSightControlsUrl = "moz-extension://"+dynamicAddonId.toString()+"/popup/controls.html";
        log.info("OdoSight Options page @ {}", odoSightOptionsUrl);

        //Initialize the login operation.
        Login login = new Login(config.getString(ExploreRequestFields.STARTING_URL.field()), config.getString(ExploreRequestFields.CANVAS_USERNAME.field()), config.getString(ExploreRequestFields.CANVAS_PASSWORD.field()) );
        loginOperation = new Operation( Operation.OperationType.INTRANSITIVE);
        loginOperation.setExecuteMethod(login::login);

        //Initialize the logout operation
        Logout logout = new Logout();
        logoutOperation = new Operation(Operation.OperationType.INTRANSITIVE);
        logoutOperation.setExecuteMethod(logout::logout);


        //Initialize the resources list
       resourcesList = config.getJsonArray(ExploreRequestFields.COURSES.field())
               .stream()
               .map(o->(String)o)
               .map(s->ResourceManager.loadCourse(s))
               .collect(Collectors.toList());

       //Initalize the input manifest
       inputManifest = config.getJsonArray(ExploreRequestFields.MANIFEST.field()).stream().map(o->(JsonObject)o).collect(Collectors.toList());

       //Construct primaryToDo list.
       inputManifest.forEach(e->{
           Operation operation = constructOperation(e);
           primaryToDo.add(operation);
           if(operation.isCompleted()){
               //Keep track of completed operations.
               completedOperations.add(operation.getId());
           }

       });

       log.info("primaryToDo size: {}", primaryToDo.size());

       //Load progress info if it exists
        if(config.containsKey("recordingCount")){
            recordingCount = config.getInteger("recordingCount")+1;
        }

        if(config.containsKey("completedOperations")){
            JsonArray _completedOperations = config.getJsonArray("completedOperations");
            completedOperations = _completedOperations.stream().map(s->UUID.fromString((String)s)).collect(Collectors.toSet());
        }

        if(config.containsKey("runtimeData")){
            JsonObject runtimeData = config.getJsonObject("runtimeData");
            resourcesList.forEach(resources->resources.loadRuntimeData(runtimeData));
        }

    }

    @Override
    public void run() {
        int completedCases = 0;

//        //START DEBUGGING
//        //Filter out various other tasks for debugging purposes.
//        primaryToDo = primaryToDo.stream()
//                .filter(operation -> operation.getResource().equals(Course.class) || operation.getResource().equals(Page.class) ).collect(ToDo::new, ToDo::add, ToDo::addAll);
//
//        //END DEBUGGING


        /* Create an Operation failures object to store operations that throw execptions during execution.
         * We'll use this to try and determine what's going on.
         */

        OperationFailures failures = new OperationFailures();



            while (primaryToDo.size() > 0){
                try{

                FirefoxOptions options = new FirefoxOptions();

                options.setProfile(buildProfile());


                driver = new FirefoxDriver(options);

                driver.installExtension(Path.of(config.getString(ExploreRequestFields.ODOSIGHT_PATH.field)), true);

                //Move browser to right screen

                //Setup OdoSight
                setupOdoSight();

                //Start the OdoSight recording
                startRecording();

                Instant start = Instant.now();

                //Each session starts by logging in.
                loginOperation.execute(driver);

                    Operation op = nextOperation();
                    if(op == null){
                        break;
                    }

                    try{
                        log.info("[{}/{}]Executing: {}",completedCases, primaryToDo.size(), op.toJson().encodePrettily());
                        op.execute(driver);
                        completedOperations.add(op.getId());
                        completedCases++;
                        failures.operationSucceeded();

                    }catch (Exception e){
                        log.info("Exception: {} type: {}", e, e.getClass().getName()  );
                        Throwable targetException = ((InvocationTargetException)((RuntimeException)e).getCause()).getTargetException();

                        failures.add(new OperationFailure(op, targetException, driver.getCurrentUrl()));
                        saveFailures(failures);

                        //For the last ones
                        //completedOperations.add(op.getId());
//                        //TODO -> Implement this in a more clear way: We want an Edit failure due to null pointer not be re-attempted
//                        //This is because an edit on something that cannot be found is likely due to a restart data issue. IE: a quiz question was created twice under different quizzes.
//                        if(targetException.getClass().equals(NullPointerException.class) && op.getType().equals(Operation.OperationType.EDIT)){
//                            log.info("Edit failure with NullPointerException counting this operation as completed! {}", op.getId());
//                            completedOperations.add(op.getId());
//                        }

                        log.warn(e.getMessage(), e);
                        e.printStackTrace();

                        if(op.getType().equals(Operation.OperationType.CREATE)){
                            log.warn("pruning dependents");
                            primaryToDo = pruneDependents(op, primaryToDo);
                        }

                        //Restart everything if this happens. I feel like this might help.
                        stopRecording();

//                        Instant end = Instant.now();
//                        log.info("took {}ms", end.toEpochMilli()-start.toEpochMilli());
//
//                        log.info("primaryToDo: {}", primaryToDo.size());
//
//                        driver.quit();
//
//                        options = new FirefoxOptions();
//
//                        options.setProfile(buildProfile());
//
//                        driver = new FirefoxDriver(options);
//
//                        driver.installExtension(Path.of(config.getString(ExploreRequestFields.ODOSIGHT_PATH.field)), true);
//                        //Setup OdoSight
//                        setupOdoSight();
//
//                        //Start the OdoSight recording
//                        startRecording();
//
//                        start = Instant.now();
//
//                        //Each session starts by logging in.
//                        loginOperation.execute(driver);






                    }finally {


                        //Now remove the session operations from the primaryToDo
                        primaryToDo = primaryToDo.stream().filter(operation -> !completedOperations.contains(operation.getId())).collect(ToDo::new, ToDo::add, ToDo::addAll);
                        saveProgress();
                    }



                //Then logging out.
                try{
                    logoutOperation.execute(driver);
                }catch (Exception e){
                    log.error("Error during logout!");
                    log.error(e.getMessage(), e);
                }


                stopRecording();

                Instant end = Instant.now();
                log.info("took {}ms", end.toEpochMilli()-start.toEpochMilli());

                log.info("primaryToDo: {}", primaryToDo.size());



                }catch (Exception e){

                    log.error(e.getMessage(), e);
                    //throw new RuntimeException(e);
                }finally {
                    if(driver != null){
                        driver.quit();
                    }

                }
            }

    }

    /**
     * Starts a new OdoSight recording, with the name specified in the config.
     */
    private void startRecording(){
        recordingCount+=1;
        //Compute the flight(recording) name and set it as the currentFlight
        currentFlight = config.getString(ExploreRequestFields.ODOSIGHT_FLIGHT_PREFIX.field) + recordingCount;

        //Switch to the OdoSight Controls tab.
        driver.switchTo().window(odoSightControlsTabHandle);

        //Click the edit flight button
        WebElement editFlightButton = driver.findElement(By.id(ODOSIGHT_CONTROLS_EDIT_FLIGHT_BUTTON_ID));
        editFlightButton.click();

        //Wait for the registered application list to appear
        explicitlyWait(driver, 1);

        //Select the application specified by the id in the explore request
        WebElement selectApplicationButton = driver.findElement(By.id(config.getString(ExploreRequestFields.LOGUI_APPLICATION_ID.field) + "-select"));
        selectApplicationButton.click();

        //Wait until the new flight button is displayed
        WebElement newFlightButton = driver.findElement(By.id(ODOSIGHT_CONTROLS_NEW_FLIGHT_BUTTON_ID));
        explicitlyWaitUntil(driver,30, d->newFlightButton.isDisplayed());

        //Enter the name for the current flight
        WebElement newFlightNameInput = driver.findElement(By.id(ODOSIGHT_CONTROLS_NEW_FLIGHT_NAME_INPUT_ID));
        newFlightNameInput.clear();
        newFlightNameInput.sendKeys(currentFlight);

        //Then click the new flight button
        newFlightButton.click();

        //Start the recording
        WebElement startRecordingButton = driver.findElement(By.id(ODOSIGHT_CONTROLS_START_RECORDING_BUTTON_ID));
        explicitlyWaitUntil(driver, 30, d->startRecordingButton.isDisplayed());
        startRecordingButton.click();

        //Wait for recording session id to be displayed
        WebElement sessionIdLabel = driver.findElement(By.id(ODOSIGHT_CONTROLS_SESSION_ID_LABEL_ID));
        explicitlyWaitUntil(driver, 30, d->!sessionIdLabel.getText().equals("<no active session>"));

        //Then return back to the web application tab
        driver.switchTo().window(webAppTabHandle);

        /**  TODO - capturing console.log() events from firefox is a bit of an ordeal so skipping for now
         *   In theory this would be a good feature for verifying that Odo Sight is in fact started properly.
         *  For more details see:
         *  https://github.com/mozilla/geckodriver/issues/284
         *  https://github.com/mozilla/geckodriver/issues/284#issuecomment-477677764
         */

    }

    private void stopRecording(){

        driver.switchTo().window(odoSightControlsTabHandle);

        //Get the stop recording button and click it to stop the recording.
        WebElement stopRecordingButton = driver.findElement(By.id(ODOSIGHT_CONTROLS_STOP_RECORDING_BUTTON_ID));
        click(driver, stopRecordingButton);
        //stopRecordingButton.click();

        explicitlyWait(driver, 2);

//TODO - Disabling scrape after every case since data generation slowed significantly over long runs, with elastic search crashing. 
//        //Enter the target elasticsearch index for the scrape operation
//        String targetIndex = config.getString(ExploreRequestFields.ODOSIGHT_FLIGHT_PREFIX.field);
//        if(targetIndex.endsWith("-") || targetIndex.endsWith("_")){ //Strip the hyphen/underscore off the flight-prefix if it exists
//            targetIndex = targetIndex.substring(0, targetIndex.length()-1);
//        }
//        WebElement scrapeIndexInput = driver.findElement(By.id(ODOSIGHT_CONTROLS_SCRAPE_ES_INDEX_INPUT_ID));
//        scrapeIndexInput.sendKeys(targetIndex);
//
//        //Get the scrape mongo button and click it to send the flight recording data to elasticsearch
//        WebElement scrapeMongoButton = driver.findElement(By.id(ODOSIGHT_CONTROLS_SCRAPE_MONGO_BUTTON_ID));
//        scrapeMongoButton.click();
//
//        explicitlyWait(driver, 2);

        driver.switchTo().window(webAppTabHandle);
    }

    private void setupOdoSight() throws InterruptedException {


        /**
         * This method is the first thing that is called after the browser opens, let's give OdoSight a chance to initialize before we call its option page.
         * If we don't do this, the default option values that the extension setup when initializing won't be populated in localStorage which means
         * they won't show up on the options page, which means we'll overwrite option values with blanks, which we do not want.
         */
        explicitlyWait(driver, 3);


        //Load the OdoSight options page
        driver.get(odoSightOptionsUrl);

        //Get the username input field, clear any existing value, then set it to the value specified in the request config
        WebElement usernameInput = driver.findElement(By.id(ODOSIGHT_OPTIONS_LOGUI_SERVER_USERNAME_FIELD_ID));
        usernameInput.clear();
        usernameInput.sendKeys(config.getString(ExploreRequestFields.ODOSIGHT_OPTIONS_LOGUI_USERNAME.field));

        //Get the password input field, clear any existing value, then set it to the value specified in the request config
        WebElement passwordInput = driver.findElement(By.id(ODOSIGHT_OPTIONS_LOGUI_SERVER_PASSWORD_FIELD_ID));
        passwordInput.clear();
        passwordInput.sendKeys(config.getString(ExploreRequestFields.ODOSIGHT_OPTIONS_LOGUI_PASSWORD.field));

        //Update the OdoSight Options with the username and password
        WebElement submitButton = findElement(driver, By.tagName("button"));
        click(driver, submitButton);
        //submitButton.click();

        //Load the web app URL
        driver.get(config.getString(ExploreRequestFields.WEB_APP_URL.field));

        //Open a new tab to access the odosight controls

        //First save the handle to this tab
        webAppTabHandle = driver.getWindowHandle();

        //Then create a new tab and switch to it.
        driver.switchTo().newWindow(WindowType.TAB);

        //Then save the handle to the new tab
        odoSightControlsTabHandle = driver.getWindowHandle();

        //Then open the odoSight controls in the new tab
        driver.get(odoSightControlsUrl);

        //Then switch back to the webAppTab
        driver.switchTo().window(webAppTabHandle);

    }

    /**
     * Removes dependent tasks of the same resource type from primary todo. Used to recover when there is an error executing a particular operation
     * @param failedOperation the operation that failed.
     */
    private ToDo pruneDependents(Operation failedOperation, ToDo target){

        JsonObject relatedIdentifiers = failedOperation.getRelatedIdentifiers();

        String key = switch (failedOperation.getResource().getName()){
            case "ca.ualberta.odobot.explorer.canvas.resources.Quiz"  -> "quiz";
            case "ca.ualberta.odobot.explorer.canvas.resources.Module" -> "module";
            case "ca.ualberta.odobot.explorer.canvas.resources.QuizQuestion" -> "question";
            case "ca.ualberta.odobot.explorer.canvas.resources.Course" -> "course";
            case "ca.ualberta.odobot.explorer.canvas.resources.Assignment" -> "assignment";
            case "ca.ualberta.odobot.explorer.canvas.resources.Page" -> "page";
            default -> throw new RuntimeException("Unrecognized resource");
        };

        //Store the value of the key from the failed operation.
        String value = relatedIdentifiers.getString(key);

        return target.stream().filter(operation -> !(operation.getRelatedIdentifiers().containsKey(key) && operation.getRelatedIdentifiers().getString(key).equals(value))).collect(ToDo::new, ToDo::add, ToDo::addAll);


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

    private Operation nextOperationInOrder(){
        return primaryToDo.get(0);
    }

    private Operation nextOperation(){
        if(primaryToDo.size() == 0){
            return null;
        }

        //Pick a random operation from the primaryToDo.
        Operation candidate = primaryToDo.randomOperation();

        Set<UUID> completedAndPlannedDependencies = new HashSet<>();
        completedAndPlannedDependencies.addAll(completedOperations);


        //If all of its dependencies have been completed, add it to our result (session)
        if(completedAndPlannedDependencies.containsAll(candidate.dependencies())){
            return candidate;
        }else{
            return findCandidate(candidate);
        }

    }

    private Operation findCandidate(Operation candidate){

        Set<UUID> completedAndPlannedDependencies = new HashSet<>();
        completedAndPlannedDependencies.addAll(completedOperations);

        List<UUID> incompleteDependencies = candidate.dependencies().stream().filter(dependency->!completedAndPlannedDependencies.contains(dependency)).collect(Collectors.toList());
        Iterator<UUID> incompleteDependenciesIt = incompleteDependencies.iterator();
        Operation next =  primaryToDo.getOperationById(incompleteDependenciesIt.next());

        //It's possible that dependencies have been pruned from the primaryToDO so we have to keep going through them until we find one.
        while (next == null){
            if(!incompleteDependenciesIt.hasNext()){
                return null;
            }
            next = primaryToDo.getOperationById(incompleteDependenciesIt.next());

        }

        if(completedOperations.containsAll(next.dependencies())){
            return next;
        }else {
            return findCandidate(next);
        }
    }

    /**
     * Re-construct an operation from it's JSON record, assuming its resources are loaded.
     * @param data
     * @return
     */
    private Operation constructOperation(JsonObject data){

        Operation.OperationType type = Operation.OperationType.valueOf(data.getString("type"));
        Operation result = constructOperation(data, type);

        return result;
    }


    /**
     * Some black magic to allow us to reuse {@link #constructOperation(JsonObject, Operation.OperationType)} for create, edit, and delete operations.
     *
     * TODO: A better design would avoid this and be much more readable/maintainable.
     *
     * @param operations
     * @param methodName
     * @return
     */
    private static Consumer<WebDriver> toConsumer(Object operations, String methodName){
        try{
            //Get the correct method from the operations class, method options should be "create", "edit", or "delete".
            Method m = operations.getClass().getMethod(methodName, WebDriver.class);

            return (driver)-> {
                try {
                    m.invoke(operations, driver);
                } catch (IllegalAccessException e) {
                    log.error(e.getMessage(), e);
                    throw new RuntimeException(e);
                } catch (InvocationTargetException e) {
                    log.error(e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            };

        }catch (Exception e){
            log.error(e.getMessage(), e);
        }

        return null;
    }

    private Operation constructOperation(JsonObject data, Operation.OperationType type){
        Operation result = new Operation(type);

        UUID operationId = UUID.fromString(data.getString("id"));
        JsonObject relatedIdentifiers = data.getJsonObject("relatedIdentifiers");
        CourseResources resources = getResourcesForCourseIdentifier(relatedIdentifiers.getString("course"));
        Course course = resources.getCourse();
        result.setRelatedIdentifiers(relatedIdentifiers);
        result.addDependency(data.getJsonArray("dependencies"));
        result.setCompleted(Boolean.parseBoolean(data.getString("isCompleted")));
        result.setId(operationId);


        String resource = data.getString("resource");
        return switch (resource){
            //Reconstruct a create course operation
            case "ca.ualberta.odobot.explorer.canvas.resources.Course" -> {
                CourseOperations courseOperations = new CourseOperations(course);
                result.setResource(Course.class);
                result.setExecuteMethod(toConsumer(courseOperations, type.name().toLowerCase()));
                yield result;
            }
            case "ca.ualberta.odobot.explorer.canvas.resources.Module" -> {
                ModuleOperations moduleOperations = new ModuleOperations(course, resources.getModuleByIdentifier(relatedIdentifiers.getString("module")));
                result.setResource(Module.class);
                result.setExecuteMethod(toConsumer(moduleOperations, type.name().toLowerCase()));
                yield result;
            }
            case "ca.ualberta.odobot.explorer.canvas.resources.Assignment" -> {
                AssignmentOperations assignmentOperations = new AssignmentOperations(course, resources.getAssignmentByIdentifier(relatedIdentifiers.getString("assignment")));
                result.setResource(Assignment.class);
                result.setExecuteMethod(toConsumer(assignmentOperations, type.name().toLowerCase()));
                yield result;
            }
            case "ca.ualberta.odobot.explorer.canvas.resources.QuizQuestion" -> {
                QuizQuestionOperations quizQuestionOperations = new QuizQuestionOperations(course, resources.getQuizByIdentifier(relatedIdentifiers.getString("quiz")), resources.getQuizQuestionByIdentifier(relatedIdentifiers.getString("question")));
                result.setResource(QuizQuestion.class);
                result.setExecuteMethod(toConsumer(quizQuestionOperations, type.name().toLowerCase()));
                yield result;
            }
            case "ca.ualberta.odobot.explorer.canvas.resources.Quiz" -> {
                QuizOperations quizOperations = new QuizOperations(course, resources.getQuizByIdentifier(relatedIdentifiers.getString("quiz")));
                result.setResource(Quiz.class);
                result.setExecuteMethod(toConsumer(quizOperations, type.name().toLowerCase()));
                yield result;
            }
            case "ca.ualberta.odobot.explorer.canvas.resources.Page" ->{
                PageOperations pageOperations = new PageOperations(course, resources.getPageIdentifier(relatedIdentifiers.getString("page")));
                result.setResource(Page.class);
                result.setExecuteMethod(toConsumer(pageOperations, type.name().toLowerCase()));
                yield result;
            }
            default -> null;
        };

    }

    private CourseResources getResourcesForCourseIdentifier(String identifier){
        return resourcesList.stream().filter(resources -> resources.getCourse().getIdentifier().equals(identifier)).findFirst().orElse(null);
    }

    /**
     * Create a JSON snapshot of the currently running explore task
     * @return
     */
    private JsonObject toJson(){

        JsonObject result = new JsonObject();
        //Bring in the config for the task
        result.mergeIn(config);

        //Update the manifest value using the primary toDo
        result.put(ExploreRequestFields.MANIFEST.field(), primaryToDo.toManifest());

        //Save the recording count
        result.put("recordingCount", recordingCount);
        result.put("completedOperations", completedOperations.stream().map(uuid -> uuid.toString()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll)); //TODO

        //Add the runtime data
        JsonObject runtimeData = new JsonObject();
        for(CourseResources resources: resourcesList){
               runtimeData.mergeIn(resources.getRuntimeValues());
        }

        result.put("runtimeData", runtimeData);

        return result;
    }

    private void saveFailures(OperationFailures failures){
        try{
            Path errorFilePath = Path.of(config.getString(ExploreRequestFields.ERROR_PATH.field()));
            if(!Files.exists(errorFilePath)){
                new File(errorFilePath.toString()).createNewFile();
            }
            Files.write(errorFilePath, failures.fullReport().encodePrettily().getBytes(), StandardOpenOption.TRUNCATE_EXISTING);

        }catch (IOException e){
            log.error(e.getMessage(),e);
        }
    }

    private void saveProgress(){

        try{

            File saveDir = new File(config.getString(ExploreRequestFields.SAVE_PATH.field()));
            if(!saveDir.exists()){
                Files.createDirectories(Path.of(saveDir.getPath()));
            }
            File dataFile = new File(saveDir.getPath() + File.separator + dynamicAddonId.toString() + ".json");
            if(!dataFile.exists()){
                dataFile.createNewFile();
            }
            Files.write(Path.of(dataFile.getPath()), toJson().encode().getBytes(), StandardOpenOption.TRUNCATE_EXISTING);


        }catch (IOException e){
            log.error(e.getMessage(), e);
        }



    }

}
