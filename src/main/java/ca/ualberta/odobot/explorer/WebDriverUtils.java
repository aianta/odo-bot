package ca.ualberta.odobot.explorer;

import ca.ualberta.odobot.explorer.canvas.resources.Course;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.Wait;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Function;

public class WebDriverUtils {

    private static final Logger log = LoggerFactory.getLogger(WebDriverUtils.class);

    public static void scrollToElement(WebDriver driver, WebElement target, By by, int numRetries){
        try{
            String javascript = """
                window.scrollTo(%s,%s);
                """.formatted(
                    target.getLocation().getX(),
                    target.getLocation().getY());
            ((JavascriptExecutor)driver).executeScript(javascript);
        }catch (StaleElementReferenceException e){
            log.warn(e.getMessage());
            while (numRetries > 0){
                explicitlyWait(driver, 1);
                target = findElement(driver, by);
                scrollToElement(driver, target, by, numRetries-1);
            }

        }

    }

    public static void hardWait(long millis){
        try{
            Thread.sleep( millis);
        }catch (InterruptedException e){
            log.error(e.getMessage(), e);
        }
    }


    public static WebElement findElement(WebDriver driver, By by){
        try{
            Thread.sleep(1000);
        }catch (InterruptedException e){
            log.error(e.getMessage(), e);
        }

        //Check for auto-save feature popup and close it if it is found.
        if(!driver.findElements(By.xpath("//h2[contains(.,'Found auto-saved content')]")).isEmpty()){
            WebElement closeButton = driver.findElement(By.xpath("//span[@data-cid='CloseButton']"));
            closeButton.click();
            explicitlyWait(driver, 2);
        }

        explicitlyWaitForElement(driver, by);
        WebElement element = driver.findElement(by);

        //Once the element is found, scroll to it to ensure it is in view for further action.
        scrollToElement(driver, element, by, 3);
        return element;
    }

    public static void explicitlyWait(WebDriver driver, int seconds){
        try{
            Thread.sleep(seconds*1000);
        }catch (InterruptedException e){
            log.error(e.getMessage(), e);
        }
    }

    public static void explicitlyWaitForElement(WebDriver driver, By idMethod){

        try{
            explicitlyWaitUntil(driver, 30, d-> ExpectedConditions.elementToBeClickable(driver.findElement(idMethod)));

        }catch (TimeoutException e){
            //Try refreshing the page and finding the element, this might just mess things up more.
            driver.navigate().refresh();
            explicitlyWaitUntil(driver, 30, d-> ExpectedConditions.elementToBeClickable(driver.findElement(idMethod)));

        }
    }

    public static void explicitlyWaitUntil(WebDriver driver, int secondsTimout, Function<? super WebDriver, Object> lambda){
        Wait<WebDriver> wait = new FluentWait<>(driver)
                .withTimeout(Duration.ofSeconds(secondsTimout))
                        .pollingEvery(Duration.ofMillis(500))
                .ignoring(NoSuchElementException.class)
                .ignoring(ElementNotInteractableException.class)
                ;
        wait.until(lambda);
    }

    public static void doubleClick(WebDriver driver, By by, Consumer<WebDriver> beforeRetry){
        click(driver, by, 2, 3, beforeRetry);
    }
    public static void doubleClick(WebDriver driver, By by){
        click(driver, by, 2, 3, null);
    }

    public static void click(WebDriver driver, WebElement element){
        click(driver, element, 1,3,null);
    }
    public static void click(WebDriver driver, By by){
        click(driver, by, 1, 3, null);
    }
    public static void click(WebDriver driver, By by, Consumer<WebDriver> beforeRetry){
        click(driver, by, 1, 3, beforeRetry);
    }

    private static void click(WebDriver driver, WebElement element, int numClicks, int numRetries, Consumer<WebDriver> beforeRetry){
        int originalNumClicks = numClicks;
        WebElement target = element;
        try{
            while (numClicks > 0){
                target.click();
                numClicks--;
            }
        }catch ( ElementClickInterceptedException e){
            log.info("Clicking through javascript");
            explicitlyWait(driver, 1);
            while (numClicks > 0){
                ((JavascriptExecutor)driver).executeScript("arguments[0].click();", target);
                numClicks--;
            }
        }catch (ElementNotInteractableException e){
            log.info("Clicking through javascript");
            explicitlyWait(driver, 1);
            while (numClicks > 0){
                ((JavascriptExecutor)driver).executeScript("arguments[0].click();", target);
                numClicks--;
            }

        }
    }

    private static void click(WebDriver driver, By by, int numClicks, int numRetries, Consumer<WebDriver> beforeRetry){
        click(driver, findElement(driver, by), numClicks, numRetries, beforeRetry);
    }
}
