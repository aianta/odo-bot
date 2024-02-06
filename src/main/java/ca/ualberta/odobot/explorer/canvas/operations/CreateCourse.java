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

        WebElement coursesSidebarLink = driver.findElement(By.id(config.getString("coursesSidebarLinkId", "global_nav_courses_link")));
        coursesSidebarLink.click();

        WebElement allCoursesLink = driver.findElement(By.linkText(config.getString("allCoursesLinkText", "All Courses")));
        allCoursesLink.click();

        WebElement openCreateCourseModalButton = driver.findElement(By.id(config.getString("openCreateCourseModalButtonId", "start_new_course")));
        openCreateCourseModalButton.click();
        openCreateCourseModalButton.click();

        WebElement courseNameField = driver.findElement(By.id(config.getString("courseNameField", "course_name")));
        courseNameField.sendKeys(course.getName());

        WebElement createCourseButton = driver.findElement(By.xpath(config.getString("createCourseButtonXpath", "//span[contains(.,'Create course')]")));
        createCourseButton.click();

        WebElement newModuleButton = driver.findElement(By.xpath(config.getString("newModuleButtonXpath", "//div[@id='course_home_content']/div[3]/div/div/button[2]")));
        explicitlyWaitUntil(driver, 5, d->newModuleButton.isDisplayed());

        course.setCoursePageUrl(driver.getCurrentUrl());
    }
}
