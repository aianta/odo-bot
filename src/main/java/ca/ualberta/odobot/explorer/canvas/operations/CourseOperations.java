package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.canvas.resources.Course;
import ca.ualberta.odobot.explorer.model.Operation;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.By;
import org.openqa.selenium.ElementNotInteractableException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static ca.ualberta.odobot.explorer.WebDriverUtils.*;

public class CourseOperations {

    private static final Logger log = LoggerFactory.getLogger(CourseOperations.class);

    private Course course;

    public CourseOperations( Course course) {
        this.course = course;
    }

    public void delete(WebDriver driver){
        //Go to the course page
        driver.get(course.getCoursePageUrl());

        //Go to the settings section
        click(driver, By.linkText("Settings"), d->d.get(course.getCoursePageUrl()));

        //Click the delete button
        click(driver, By.linkText("Delete this Course"));

        //Confirm deletion by clicking delete course button
        click(driver, By.xpath("//button[contains(.,'Delete Course')]"));

    }

    public void create(WebDriver driver) {

        click(driver, By.id("global_nav_courses_link"));

        click(driver, By.linkText("All Courses"));

        click(driver, By.id("start_new_course"));

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        try{
            WebElement courseNameField = findElement(driver, By.id("course_name"));
            explicitlyWaitUntil(driver, 30, d-> ExpectedConditions.elementToBeClickable(courseNameField));
            courseNameField.sendKeys(course.getName());
        }catch (ElementNotInteractableException e){
            //Just try it again.
            click(driver, By.id("start_new_course"));

            WebElement courseNameField = findElement(driver, By.id("course_name"));
            courseNameField.sendKeys(course.getName());
        }


        //Click create course button
        click(driver, By.xpath("//span[contains(.,'Create course')]"));

        findElement(driver, By.xpath("//div[@id='course_home_content']/div[3]/div/div/button[2]"));

        course.setCoursePageUrl(driver.getCurrentUrl());
    }
}
