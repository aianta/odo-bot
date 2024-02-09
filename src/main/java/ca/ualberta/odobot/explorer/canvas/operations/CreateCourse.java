package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.canvas.resources.Course;
import ca.ualberta.odobot.explorer.model.Operation;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static ca.ualberta.odobot.explorer.WebDriverUtils.*;

public class CreateCourse extends Operation {

    private static final Logger log = LoggerFactory.getLogger(CreateCourse.class);

    private Course course;

    public CreateCourse(JsonObject config, Course course) {
        super(config);
        type = OperationType.CREATE;
        resource = Course.class;
        this.course = course;
    }

    @Override
    protected void _execute(WebDriver driver) {
        WebElement coursesSidebarLink = findElement(driver, By.id("global_nav_courses_link"));
        coursesSidebarLink.click();

        WebElement allCoursesLink = findElement(driver, By.linkText("All Courses"));
        allCoursesLink.click();

        WebElement openCreateCourseModalButton = findElement(driver, By.id("start_new_course"));
        openCreateCourseModalButton.click();
        try{
            openCreateCourseModalButton.click();
        }catch (Exception e){
            log.warn("double click warning");
        }
        WebElement courseNameField = findElement(driver, By.id("course_name"));
        courseNameField.sendKeys(course.getName());

        WebElement createCourseButton = findElement(driver, By.xpath("//span[contains(.,'Create course')]"));
        createCourseButton.click();

        WebElement newModuleButton = findElement(driver, By.xpath("//div[@id='course_home_content']/div[3]/div/div/button[2]"));

        course.setCoursePageUrl(driver.getCurrentUrl());
    }
}
