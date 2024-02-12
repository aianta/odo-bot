package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.model.Operation;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static ca.ualberta.odobot.explorer.WebDriverUtils.*;

public class Login {

    private static final Logger log = LoggerFactory.getLogger(Login.class);

    private String username;
    private String password;
    private String startingUrl;

    public Login(String startingUrl, String username, String password) {
        this.startingUrl = startingUrl;
        this.username = username;
        this.password = password;
    }

    public void login(WebDriver driver) {

        log.info("Current URL: {}", driver.getCurrentUrl());

        //If we're not on the login page
        if(!driver.getCurrentUrl().equals(startingUrl)){
            //Navigate to login page
            driver.get(startingUrl);
        }

        WebElement usernameField = findElement(driver, By.id("pseudonym_session_unique_id"));
        usernameField.sendKeys(username);

        WebElement passwordField = findElement(driver, By.id("pseudonym_session_password"));
        passwordField.sendKeys(password);

        WebElement loginButton = findElement(driver, By.xpath("//form[@id='login_form']/div[3]/div[2]/button"));
        loginButton.click();


    }
}
