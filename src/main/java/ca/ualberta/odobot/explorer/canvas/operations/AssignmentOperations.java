package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.canvas.resources.Assignment;
import ca.ualberta.odobot.explorer.canvas.resources.Course;
import ca.ualberta.odobot.explorer.model.MultiPath;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static ca.ualberta.odobot.explorer.WebDriverUtils.*;



public class AssignmentOperations {

    private static final Logger log = LoggerFactory.getLogger(AssignmentOperations.class);

    private Course course;
    private Assignment assignment;

    private MultiPath navigateToCoursePage = new MultiPath();

    public AssignmentOperations( Course course, Assignment assignment) {
        this.course = course;
        this.assignment = assignment;

        navigateToCoursePage.addPath(this::navOption1);
        navigateToCoursePage.setFallback(driver -> driver.get(course.getCoursePageUrl()));
    }

    public void delete(WebDriver driver){

        //Navigate to the course page
        navigateToCoursePage.getPath().accept(driver);

        //Click on the assignments section of the course
        WebElement assignmentsSectionLink = findElement(driver, By.linkText("Assignments"));
        assignmentsSectionLink.click();

        WebElement assignmentLink = findElement(driver, By.linkText(assignment.getName()));
        assignmentLink.click();

        //If we're not on the page for this assignment at this point
        if(!driver.getCurrentUrl().equals(assignment.getAssignmentPageUrl())){
            driver.get(assignment.getAssignmentPageUrl()); //Explicitly load that page
        }

        //Click the edit button
        WebElement editButton = findElement(driver, By.linkText("Edit"));
        editButton.click();

        explicitlyWait(driver, 1);

        //Click the more options drop down
        WebElement moreOptions = findElement(driver, By.className("icon-more"));
        moreOptions.click();
        moreOptions.click();

        //Click the delete option
        WebElement deleteOption = findElement(driver, By.linkText("Delete"));
        deleteOption.click();

        //At this point, an alert should come up asking us to confirm if we'd like to delete the assignment.
        //Let's switch to that alert and accept it.
        driver.switchTo().alert().accept();



    }

    public void edit (WebDriver driver){

        //Navigate to the course page
        navigateToCoursePage.getPath().accept(driver);

        //Click on the assignments section of the course
        WebElement assignmentsSectionLink = findElement(driver, By.linkText("Assignments"));
        assignmentsSectionLink.click();

        WebElement assignmentLink = findElement(driver, By.linkText(assignment.getName()));
        assignmentLink.click();

        //Wait to see the assignment title displayed
        WebElement assignmentTitle = findElement(driver, By.cssSelector(".title-content > .title"));
        explicitlyWaitUntil(driver, 30, d-> ExpectedConditions.visibilityOf(assignmentTitle));

        //If we're not on the page for this assignment at this point
        if(!driver.getCurrentUrl().equals(assignment.getAssignmentPageUrl())){
            driver.get(assignment.getAssignmentPageUrl()); //Explicitly load that page
        }

        //Click the edit button
        WebElement editButton = findElement(driver, By.linkText("Edit"));
        editButton.click();

        explicitlyWait(driver, 3);

        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent('"+assignment.makeEdit(assignment.getBody())+"')");

        //Click the save button
        WebElement saveButton = findElement(driver, By.xpath("//form[@id='edit_assignment_form']/div[3]/div[2]/button[3]"));
        saveButton.click();

        //Wait to see the assignment title displayed
        explicitlyWaitUntil(driver, 30, d-> ExpectedConditions.visibilityOf(assignmentTitle));
    }

    public void create(WebDriver driver) {

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
