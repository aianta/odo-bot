package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.canvas.resources.Assignment;
import ca.ualberta.odobot.explorer.canvas.resources.Course;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static ca.ualberta.odobot.explorer.WebDriverUtils.*;
import static ca.ualberta.odobot.explorer.canvas.operations.CourseOperations.navToCourseViaDashboardCard;
import static ca.ualberta.odobot.explorer.canvas.operations.Utils.byDeleteElement;
import static ca.ualberta.odobot.explorer.canvas.operations.Utils.byEditElement;


public class AssignmentOperations {

    private static final Logger log = LoggerFactory.getLogger(AssignmentOperations.class);

    private Course course;
    private Assignment assignment;

    public AssignmentOperations( Course course, Assignment assignment) {
        this.course = course;
        this.assignment = assignment;

    }

    public void delete(WebDriver driver){

        //Navigate to the assignment page
        navToAssignmentPage(driver, course, assignment);


        //Click the edit button
        click(driver, byEditElement);

        explicitlyWait(driver, 1);

        //Click the more options drop down
        click(driver, By.className("icon-more"));

        //Click the delete option
        click(driver, byDeleteElement);

        //At this point, an alert should come up asking us to confirm if we'd like to delete the assignment.
        //Let's switch to that alert and accept it.
        driver.switchTo().alert().accept();



    }

    public void edit (WebDriver driver){

        //Navigate to the assignment page
        navToAssignmentPage(driver, course, assignment);

        //Click the edit button
        click(driver, byEditElement);

        explicitlyWait(driver, 3);

        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent(`"+assignment.makeEdit(assignment.getBody())+"`)");

        //Click the save button
        click(driver, By.xpath("//form[@id='edit_assignment_form']/div[3]/div[2]/button[3]") );

        //Wait to see the assignment title displayed
        explicitlyWait(driver, 1);
    }

    public void create(WebDriver driver) {

        //Navigate to the course assignments page
        navToAssignmentsPage(driver, course);

        //Click the new Assignment button
        click(driver, By.cssSelector("a[title='Add Assignment']") );

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

    private void navToAssignmentPage(WebDriver driver, Course course, Assignment assignment){
        navToAssignmentsPage(driver,course);

        click(driver, By.cssSelector("a[href='%s']".formatted(assignment.getAssignmentPageUrl())));
    }

    private void navToAssignmentsPage(WebDriver driver, Course course){

        //First navigate to the course page.
        navToCourseViaDashboardCard(driver, course);


        WebElement assignmentsLink = findElement(driver, By.linkText("Assignments"));
        click(driver, assignmentsLink);

    }

    private void navToCourseViaSidebar(WebDriver driver){

        WebElement coursesSideBarLink = findElement(driver, By.id("global_nav_courses_link"));
        click(driver, coursesSideBarLink);


        WebElement coursesLink = findElement(driver, By.linkText(course.getName()));
        click(driver, coursesLink);
    }


}
