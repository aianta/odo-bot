package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.canvas.resources.Assignment;
import ca.ualberta.odobot.explorer.canvas.resources.Course;
import ca.ualberta.odobot.explorer.model.MultiPath;
import ca.ualberta.odobot.explorer.model.Operation;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.Wait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static ca.ualberta.odobot.explorer.WebDriverUtils.*;



public class CreateAssignment extends Operation {

    private static final Logger log = LoggerFactory.getLogger(CreateAssignment.class);

    private Course course;
    private Assignment assignment;

    private MultiPath navigateToCoursePage = new MultiPath();

    public CreateAssignment(JsonObject config, Course course, Assignment assignment) {
        super(config);
        type = OperationType.CREATE;
        resource = Assignment.class;
        this.course = course;
        this.assignment = assignment;

        navigateToCoursePage.addPath(this::navOption1);
        navigateToCoursePage.setFallback(driver -> driver.get(course.getCoursePageUrl()));
    }

    @Override
    protected void _execute(WebDriver driver) {

        //Navigate to the course page
        navigateToCoursePage.getPath().accept(driver);

        //Click on the assignments section of the course
        WebElement assignmentsSectionLink = findElement(driver, By.linkText("Assignments"));
        assignmentsSectionLink.click();

        //Click the new Assignment button
        WebElement newAssignmentButton = findElement(driver,By.xpath("//a[@href='"+course.getCoursePageUrl()+"/assignments/new']") );
        newAssignmentButton.click();

        //Enter the assignment name
        WebElement assignmentNameField = findElement(driver, By.id("assignment_name"));
        assignmentNameField.sendKeys(assignment.getName());

        explicitlyWait(driver,2);
        //Enter the assignment content
        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent('"+assignment.getBody()+"')");


        //Choose Text Entry as the submission type
        WebElement textEntryCheckbox = findElement(driver, By.id("assignment_text_entry"));
        textEntryCheckbox.click();

        //Click the save button
        WebElement saveButton = findElement(driver, By.xpath("//form[@id='edit_assignment_form']/div[3]/div[2]/button[3]"));
        saveButton.click();

        //Wait to see the assignment title displayed
        WebElement assignmentTitle = findElement(driver, By.cssSelector(".title-content > .title"));
        explicitlyWaitUntil(driver, 30, d-> ExpectedConditions.visibilityOf(assignmentTitle));

        assignment.setAssignmentPageUrl(driver.getCurrentUrl());

    }

    private void navOption1(WebDriver driver){
        WebElement coursesSideBarLink = driver.findElement(By.id("global_nav_courses_link"));
        coursesSideBarLink.click();

        WebElement coursesLink = driver.findElement(By.linkText(course.getName()));
        coursesLink.click();
    }

}
