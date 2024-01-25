package ca.ualberta.odobot.explorer;

import co.elastic.clients.elasticsearch._types.aggregations.HdrPercentilesAggregate;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.firefox.*;
import org.openqa.selenium.support.ui.Wait;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

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
    private static final String ODOSIGHT_OPTIONS_LOGUI_CLIENT_CONFIG_FIELD_ID = "logui-client-config";


    JsonObject config;

    UUID dynamicAddonId = UUID.randomUUID();

    String odoSightOptionsUrl;

    FirefoxDriver driver;

    public ExploreTask(JsonObject config){
        this.config = config;

        /**
         * See documentation for{@link #buildProfile()}
         */
        this.odoSightOptionsUrl = "moz-extension://"+dynamicAddonId.toString()+"/options/options.html";
        log.info("OdoSight Options page @ {}", odoSightOptionsUrl);
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

            //Load the web app URL
            driver.get(config.getString(ExploreRequestFields.WEB_APP_URL.field));
        }catch (Exception e){

            log.error(e.getMessage(), e);
        }






    }

    private void setupOdoSight() throws InterruptedException {


        /**
         * This method is the first thing that is called after the browser opens, let's give OdoSight a chance to initialize before we call its option page.
         * If we don't do this, the default option values that the extension setup when initializing won't be populated in localStorage which means
         * they won't show up on the options page, which means we'll overwrite option values with blanks, which we do not want.
         */
        Instant targetTime = Instant.ofEpochSecond(Instant.now().getEpochSecond() + 3);
        Wait<WebDriver> wait = new WebDriverWait(driver, Duration.ofSeconds(5));
        wait.until(d->Instant.now().isAfter(targetTime));


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
        profile.setPreference("extensions.webextensions.uuids", addonIdPreference.encode());


        return profile;
    }

}
