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

public class Logout  {

    private static final Logger log = LoggerFactory.getLogger(Logout.class);




    public void logout(WebDriver driver) {
        try{
            click(driver, By.xpath("//button[@id='global_nav_profile_link']/div"));
            click(driver, By.xpath("//button[contains(.,'Logout')]"));
        }catch (NoSuchElementException notFound){
            log.error(notFound.getMessage(), notFound);
        }
    }
}
