package ca.ualberta.odobot.explorer;

import ca.ualberta.odobot.explorer.canvas.operations.*;
import ca.ualberta.odobot.explorer.canvas.resources.*;
import ca.ualberta.odobot.explorer.canvas.resources.Module;
import ca.ualberta.odobot.explorer.model.Operation;
import ca.ualberta.odobot.explorer.model.ToDo;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.*;
import org.openqa.selenium.firefox.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static ca.ualberta.odobot.explorer.WebDriverUtils.explicitlyWait;
import static ca.ualberta.odobot.explorer.WebDriverUtils.explicitlyWaitUntil;

public class ExploreTask implements Runnable{

    private static final Logger log = LoggerFactory.getLogger(ExploreTask.class);

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
    JsonObject config;
    UUID dynamicAddonId = UUID.randomUUID();
    String odoSightOptionsUrl;
    String odoSightControlsUrl;
    String odoSightControlsTabHandle;
    String webAppTabHandle;
    FirefoxDriver driver;
    int recordingCount = 0;
    String currentFlight;

    ToDo toDo;


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



        Course course = new Course();
        course.setName("Dummy Course 2");
        course.setCoursePageUrl("http://localhost:8088/courses/25");

        Module module = new Module();
        module.setName("Dummy Module");

        Quiz quiz = new Quiz();
        quiz.setQuizEditPageUrl("http://localhost:8088/courses/25/quizzes/37/edit");
        quiz.setQuizPageUrl("http://localhost:8088/courses/25/quizzes/37");
        quiz.setName("dummy quiz");
        quiz.setBody("Hmmm, a quiz!");

        QuizQuestion question = new QuizQuestion();
        question.setName("Question One");
        question.setType(QuizQuestion.QuestionType.MULTIPLE_CHOICE);
        question.setBody("What is the value of pi?");

        Page page = new Page();
        page.setTitle("Dummy Page");
        page.setBody("Lorem ipsum dolor sit amet, consectetur adipiscing elit. Praesent accumsan varius volutpat. Phasellus nisl enim, molestie a ligula id, tempor iaculis nisi.");

        Assignment assignment = new Assignment();
        assignment.setName("Assignment 1");
        assignment.setBody("<b>WOAH</b>");

        CourseOperations courseOperations = new CourseOperations(course);
        ModuleOperations moduleOperations = new ModuleOperations(course, module);
        QuizOperations quizOperations = new QuizOperations(course, quiz);
        QuizQuestionOperations quizQuestionOperations = new QuizQuestionOperations(course,quiz,question);
        PageOperations pageOperations = new PageOperations(course,page);
        AssignmentOperations assignmentOperations = new AssignmentOperations(course, assignment);

        Login login = new Login("http://localhost:8088/login/canvas", "ianta@ualberta.ca", config.getString(ExploreRequestFields.ODOSIGHT_OPTIONS_LOGUI_PASSWORD.field) );
        Operation loginOperation = new Operation( Operation.OperationType.INTRANSITIVE);
        loginOperation.setExecuteMethod(login::login);

        toDo = new ToDo();
        toDo.add(loginOperation);

        Operation createCourse = new Operation( Operation.OperationType.CREATE, Course.class);
        createCourse.setExecuteMethod(courseOperations::create);

        Operation createModule = new Operation( Operation.OperationType.CREATE, Module.class);
        createModule.setExecuteMethod(moduleOperations::create);

        Operation createQuiz = new Operation( Operation.OperationType.CREATE, Quiz.class);
        createQuiz.setExecuteMethod(quizOperations::create);

        Operation createQuizQuestion = new Operation( Operation.OperationType.CREATE, QuizQuestion.class);
        createQuizQuestion.setExecuteMethod(quizQuestionOperations::create);

        Operation createAssignment = new Operation( Operation.OperationType.CREATE, Assignment.class);
        createAssignment.setExecuteMethod(assignmentOperations::create);

        Operation createPage = new Operation( Operation.OperationType.CREATE, Page.class);
        createPage.setExecuteMethod(pageOperations::create);

        toDo.add(createCourse);
        toDo.add(createModule);
        toDo.add(createQuiz);
        toDo.add(createQuizQuestion);
        toDo.add(createAssignment);
        toDo.add(createPage);

        Operation editPage = new Operation(Operation.OperationType.EDIT, Page.class);
        editPage.setExecuteMethod(pageOperations::edit);

        Operation editAssignment = new Operation(Operation.OperationType.EDIT, Assignment.class);
        editAssignment.setExecuteMethod(assignmentOperations::edit);

        Operation editQuiz = new Operation(Operation.OperationType.EDIT, Quiz.class);
        editQuiz.setExecuteMethod(quizOperations::edit);

        Operation editQuizQuestion = new Operation(Operation.OperationType.EDIT, Quiz.class);
        editQuizQuestion.setExecuteMethod(quizQuestionOperations::edit);

        Operation editModule = new Operation(Operation.OperationType.EDIT, Module.class);
        editModule.setExecuteMethod(moduleOperations::edit);

        toDo.add(editPage);
        toDo.add(editAssignment);
        toDo.add(editQuizQuestion);
        toDo.add(editQuiz);
        toDo.add(editModule);

        Operation deletePage = new Operation(Operation.OperationType.DELETE, Page.class);
        deletePage.setExecuteMethod(pageOperations::delete);

        Operation deleteAssignment = new Operation(Operation.OperationType.DELETE, Assignment.class);
        deleteAssignment.setExecuteMethod(assignmentOperations::delete);

        Operation deleteQuizQuestion = new Operation(Operation.OperationType.DELETE, QuizQuestion.class);
        deleteQuizQuestion.setExecuteMethod(quizQuestionOperations::delete);

        Operation deleteQuiz = new Operation(Operation.OperationType.DELETE, Quiz.class);
        deleteQuiz.setExecuteMethod(quizOperations::delete);

        Operation deleteModule = new Operation(Operation.OperationType.DELETE, Module.class);
        deleteModule.setExecuteMethod(moduleOperations::delete);

        Operation deleteCourse = new Operation(Operation.OperationType.DELETE, Course.class);
        deleteCourse.setExecuteMethod(courseOperations::delete);

        toDo.add(deletePage);
        toDo.add(deleteAssignment);
        toDo.add(deleteQuizQuestion);
        toDo.add(deleteQuiz);
        toDo.add(deleteModule);
        toDo.add(deleteCourse);

    }

    @Override
    public void run() {

        FirefoxOptions options = new FirefoxOptions();

        options.setProfile(buildProfile());

        driver = new FirefoxDriver(options);

        driver.installExtension(Path.of(config.getString(ExploreRequestFields.ODOSIGHT_PATH.field)), true);

        try{
            //Setup OdoSight
            setupOdoSight();

            //Start the OdoSight recording
            startRecording();

            Instant start = Instant.now();

            toDo.forEach(op->{
                op.execute(driver);
                explicitlyWait(driver, 2);
            });

            Instant end = Instant.now();
            log.info("took {}ms", end.toEpochMilli()-start.toEpochMilli());

            stopRecording();

//            CrawljaxConfiguration.CrawljaxConfigurationBuilder builder = CrawljaxConfiguration.builderFor(config.getString(ExploreRequestFields.WEB_APP_URL.field));
//            CrawljaxConfiguration crawljaxConfiguration = builder.build();
//            CrawljaxRunner runner = new CrawljaxRunner(crawljaxConfiguration);
//            runner.call();

        }catch (Exception e){

            log.error(e.getMessage(), e);
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
        explicitlyWait(driver, 2);

        //Select the application specified by the id in the explore request
        WebElement selectApplicationButton = driver.findElement(By.id(config.getString(ExploreRequestFields.LOGUI_APPLICATION_ID.field) + "-select"));
        selectApplicationButton.click();

        //Wait until the new flight button is displayed
        WebElement newFlightButton = driver.findElement(By.id(ODOSIGHT_CONTROLS_NEW_FLIGHT_BUTTON_ID));
        explicitlyWaitUntil(driver,30, d->newFlightButton.isDisplayed());

        //Enter the name for the current flight
        WebElement newFlightNameInput = driver.findElement(By.id(ODOSIGHT_CONTROLS_NEW_FLIGHT_NAME_INPUT_ID));
        newFlightNameInput.sendKeys(currentFlight);

        //Then click the new flight button
        newFlightButton.click();

        //Start the recording
        WebElement startRecordingButton = driver.findElement(By.id(ODOSIGHT_CONTROLS_START_RECORDING_BUTTON_ID));
        explicitlyWaitUntil(driver, 2, d->startRecordingButton.isDisplayed());
        startRecordingButton.click();

        //Wait for recording session id to be displayed
        WebElement sessionIdLabel = driver.findElement(By.id(ODOSIGHT_CONTROLS_SESSION_ID_LABEL_ID));
        explicitlyWaitUntil(driver, 5, d->!sessionIdLabel.getText().equals("<no active session>"));

        //Then return back to the web application tab
        driver.switchTo().window(webAppTabHandle);

        /**  TODO - capturing console.log() events from firefox is a bit of an ordeal so skipping for now
         *  For more details see:
         *  https://github.com/mozilla/geckodriver/issues/284
         *  https://github.com/mozilla/geckodriver/issues/284#issuecomment-477677764
         */
//        //Ensure the recording is active by checking the browser console logs for 'LogUI' and 'Logged object below' log line entries
//        List<LogEntry> logEntries = driver.manage().logs().get(LogType.BROWSER).getAll();
//        log.info("got {} log entries in the console", logEntries.size());
//        for(int i = logEntries.size()-1; i > 0; i--){
//            LogEntry entry = logEntries.get(i);
//            if(entry.getMessage().contains("LogUI") && entry.getMessage().contains("Logged object below")){
//                log.info("OdoSight recording is active.");
//                recordingIsActive = true;
//            }
//        }
//        //Explicitly mark log entries for garbage collection
//        logEntries = null;
//
//        if(!recordingIsActive){
//            throw new RuntimeException("OdoSight did not start recording!");
//        }
    }

    private void stopRecording(){

        driver.switchTo().window(odoSightControlsTabHandle);

        //Get the stop recording button and click it to stop the recording.
        WebElement stopRecordingButton = driver.findElement(By.id(ODOSIGHT_CONTROLS_STOP_RECORDING_BUTTON_ID));
        stopRecordingButton.click();

        explicitlyWait(driver, 2);

        //Get the scrape mongo button and click it to send the flight recording data to elasticsearch
        WebElement scrapeMongoButton = driver.findElement(By.id(ODOSIGHT_CONTROLS_SCRAPE_MONGO_BUTTON_ID));
        scrapeMongoButton.click();

        explicitlyWait(driver, 2);

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
        WebElement submitButton = driver.findElement(By.tagName("button"));
        submitButton.click();

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

        ProfilesIni allProfiles = new ProfilesIni();
        FirefoxProfile profile = allProfiles.getProfile("Selenium");
        profile.setAcceptUntrustedCertificates(true); //LogUI Server is likely running locally over a self-signed cert.
        profile.setPreference("extensions.webextensions.uuids", addonIdPreference.encode());


        return profile;
    }



}
