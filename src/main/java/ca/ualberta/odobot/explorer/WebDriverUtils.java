package ca.ualberta.odobot.explorer;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.Wait;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

public class WebDriverUtils {

    public static void explicitlyWait(WebDriver driver, int seconds){
        Instant targetTime = Instant.ofEpochSecond(Instant.now().getEpochSecond() + seconds);
        Wait<WebDriver> wait = new WebDriverWait(driver, Duration.ofSeconds(seconds + 2));
        wait.until(d->Instant.now().isAfter(targetTime));
    }

    public static void explicitlyWaitUntil(WebDriver driver, int secondsTimout, Function<? super WebDriver, Object> lambda){
        Wait<WebDriver> wait = new WebDriverWait(driver, Duration.ofSeconds(secondsTimout));
        wait.until(lambda);
    }
}
