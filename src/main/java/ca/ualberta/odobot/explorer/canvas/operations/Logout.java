package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.model.Operation;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static ca.ualberta.odobot.explorer.WebDriverUtils.*;

public class Logout extends Operation {

    private static final Logger log = LoggerFactory.getLogger(Logout.class);

    public Logout(JsonObject config) {
        super(config);
        type = OperationType.INTRANSITIVE;
    }

    @Override
    protected void _execute(WebDriver driver) {

        try{
            WebElement profileNavLink = findElement(driver, By.xpath("//button[@id='global_nav_profile_link']/div"));
            profileNavLink.click();

            WebElement logoutButton = findElement(driver, By.xpath("//button[contains(.,'Logout')]"));
            logoutButton.click();


        }catch (NoSuchElementException notFound){
            log.error(notFound.getMessage(), notFound);
        }
    }
}
