package ca.ualberta.odobot.explorer;

import ca.ualberta.odobot.explorer.canvas.resources.Course;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.Wait;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

public class WebDriverUtils {

    private static void navigateToCoursePageCommon(WebDriver driver, Course course){
        WebElement coursesSidebarLink = findElement(driver, By.id("global_nav_courses_link"));
        coursesSidebarLink.click();
    }

    public static void navigateToCoursePage2(WebDriver driver, Course course){
        navigateToCoursePageCommon(driver, course);

        WebElement courseLink = findElement(driver, By.linkText(course.getName()));
        courseLink.click();
    }

    public static void navigateToCoursePage1(WebDriver driver, Course course){

        navigateToCoursePageCommon(driver, course);

        WebElement allCoursesLink = findElement(driver, By.linkText("All Courses"));
        allCoursesLink.click();

        WebElement courseLink = findElement(driver, By.xpath("//a[@href='"+course.getCoursePageUrlAsURL().getPath()+"']"));
        courseLink.click();

    }

    public static WebElement findElement(WebDriver driver, By by){
        explicitlyWaitForElement(driver, by);
        return driver.findElement(by);
    }

    public static void explicitlyWait(WebDriver driver, int seconds){
        Instant targetTime = Instant.ofEpochSecond(Instant.now().getEpochSecond() + seconds);
        Wait<WebDriver> wait = new WebDriverWait(driver, Duration.ofSeconds(seconds + 2));
        wait.until(d->Instant.now().isAfter(targetTime));
    }

    public static void explicitlyWaitForElement(WebDriver driver, By idMethod){
        explicitlyWaitUntil(driver, 10, d-> ExpectedConditions.elementToBeClickable(driver.findElement(idMethod)));
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
}
