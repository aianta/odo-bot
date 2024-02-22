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

    public AssignmentOperations( Course course, Assignment assignment) {
        this.course = course;
        this.assignment = assignment;

    }

    public void delete(WebDriver driver){

        //Navigate to the course assignment page
        driver.get(course.getCoursePageUrl()+"/assignments");


        click(driver, By.xpath("//a[@href='http://localhost:8088/courses/"+course.getId()+"/assignments/"+assignment.getId()+"']"));

        //If we're not on the page for this assignment at this point
        if(!driver.getCurrentUrl().equals(assignment.getAssignmentPageUrl())){
            driver.get(assignment.getAssignmentPageUrl()); //Explicitly load that page
        }

        //Click the edit button
        click(driver, By.linkText("Edit"));

        explicitlyWait(driver, 1);

        //Click the more options drop down
        click(driver, By.className("icon-more"));

        //Click the delete option
        click(driver, By.linkText("Delete"));

        //At this point, an alert should come up asking us to confirm if we'd like to delete the assignment.
        //Let's switch to that alert and accept it.
        driver.switchTo().alert().accept();



    }

    public void edit (WebDriver driver){

        //Navigate to the course assignments page
        driver.get(course.getCoursePageUrl() + "/assignments");
        click(driver, By.xpath("//a[@href='http://localhost:8088/courses/"+course.getId()+"/assignments/"+assignment.getId()+"']"));

        //Wait to see the assignment title displayed
        WebElement assignmentTitle = findElement(driver, By.cssSelector(".title-content > .title"));
        explicitlyWaitUntil(driver, 30, d-> ExpectedConditions.visibilityOf(assignmentTitle));

        //If we're not on the page for this assignment at this point
        if(!driver.getCurrentUrl().equals(assignment.getAssignmentPageUrl())){
            driver.get(assignment.getAssignmentPageUrl()); //Explicitly load that page
        }

        //Click the edit button
        click(driver, By.linkText("Edit"));

        explicitlyWait(driver, 3);

        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent(`"+assignment.makeEdit(assignment.getBody())+"`)");

        //Click the save button
        click(driver, By.xpath("//form[@id='edit_assignment_form']/div[3]/div[2]/button[3]") );

        //Wait to see the assignment title displayed
        explicitlyWaitUntil(driver, 30, d-> ExpectedConditions.visibilityOf(assignmentTitle));
    }

    public void create(WebDriver driver) {

        //Navigate to the course assignments page
        driver.get(course.getCoursePageUrl() + "/assignments");

        //Click the new Assignment button
        click(driver,By.xpath("//a[@href='"+course.getCoursePageUrl()+"/assignments/new']") );

        //Enter the assignment name
        WebElement assignmentNameField = findElement(driver, By.id("assignment_name"));
        assignmentNameField.sendKeys(assignment.getName());

        explicitlyWait(driver,2);
        //Choose Text Entry as the submission type
        click(driver, By.id("assignment_text_entry"));
        explicitlyWaitUntil(driver, 30, d->ExpectedConditions.elementSelectionStateToBe(findElement(driver, By.id("assignment_text_entry")), true));

        //Enter the assignment content
        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent(`"+assignment.getBody()+"`)");

        WebElement textEntryCheckbox = findElement(driver, By.id("assignment_text_entry"));
        if(!textEntryCheckbox.isSelected()){
            click(driver,  By.id("assignment_text_entry"));
        }

        //Click the save button
        click(driver, By.xpath("//form[@id='edit_assignment_form']/div[3]/div[2]/button[3]"));

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
