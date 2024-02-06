package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.model.Operation;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Login extends Operation {

    private static final Logger log = LoggerFactory.getLogger(Login.class);

    public Login(JsonObject config) {
        super(config);
        type = OperationType.INTRANSITIVE;
    }

    @Override
    protected void _execute(WebDriver driver) {

        log.info("Current URL: {}", driver.getCurrentUrl());

        //If we're not on the login page
        if(!driver.getCurrentUrl().equals(config.getString("startingUrl"))){
            //Navigate to login page
            driver.get(config.getString("startingUrl"));
        }

        WebElement usernameField = driver.findElement(By.id(config.getString("usernameFieldId", "pseudonym_session_unique_id")));
        usernameField.sendKeys(config.getString("username"));

        WebElement passwordField = driver.findElement(By.id(config.getString("passwordFieldId", "pseudonym_session_password")));
        passwordField.sendKeys(config.getString("password"));

        WebElement loginButton = driver.findElement(By.xpath(config.getString("loginButtonXpath", "//form[@id='login_form']/div[3]/div[2]/button")));
        loginButton.click();


    }
}
