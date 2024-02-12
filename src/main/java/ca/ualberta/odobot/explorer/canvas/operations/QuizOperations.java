package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.canvas.resources.Course;
import ca.ualberta.odobot.explorer.canvas.resources.Quiz;
import ca.ualberta.odobot.explorer.model.MultiPath;
import org.openqa.selenium.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static ca.ualberta.odobot.explorer.WebDriverUtils.*;

public class QuizOperations {
    private static final Logger log = LoggerFactory.getLogger(QuizOperations.class);

    private Course course;

    private Quiz quiz;

    private MultiPath navigateToCoursePage = new MultiPath();

    public QuizOperations(Course course, Quiz quiz) {
        this.course = course;
        this.quiz = quiz;

        this.navigateToCoursePage.addPath(d->navigateToCoursePage1(d, course));
        this.navigateToCoursePage.setFallback(driver->driver.get(course.getCoursePageUrl()));
    }

    public void delete(WebDriver driver){
        //Navigate to the course page
        navigateToCoursePage.getPath().accept(driver);

        //Then to the quizzes section
        WebElement quizzesSectionLink = findElement(driver, By.linkText("Quizzes"));
        quizzesSectionLink.click();

        //Then click on the quiz to delete
        WebElement quizLink = findElement(driver, By.xpath("//a[@href='"+quiz.getQuizPageUrl()+"']"));
        quizLink.click();

        //Find the drop-down menu button
        WebElement dropDown = findElement(driver, By.xpath("//button[contains(.,'Manage')]"));
        dropDown.click();
        dropDown.click();

        //Find the delete option
        WebElement deleteOption = findElement(driver, By.className("delete_quiz_link"));
        deleteOption.click();

        //At this point an alerta should have popped up asking if we're really sure about deleting the quiz
        driver.switchTo().alert().accept();

    }

    public void edit(WebDriver driver){
        //Navigate to the course page
        navigateToCoursePage.getPath().accept(driver);

        //Then to the quizzes section
        WebElement quizzesSectionLink = findElement(driver, By.linkText("Quizzes"));
        quizzesSectionLink.click();

        //Then click on the quiz to edit
        WebElement quizLink = findElement(driver, By.xpath("//a[@href='"+quiz.getQuizPageUrl()+"']"));
        quizLink.click();

        //Then click the edit button
        WebElement editButton = findElement(driver, By.linkText("Edit"));
        editButton.click();

        explicitlyWait(driver, 3);

        //Edit the quiz body
        if(quiz.getBody().isBlank()){
            quiz.setBody(quiz.getName() + " body");
        }
        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent('"+ quiz.makeEdit(quiz.getBody())+"')");

        //Click the save quiz button
        WebElement saveQuizButton = findElement(driver, By.className("save_quiz_button"));
        saveQuizButton.click();

    }

    public void create(WebDriver driver) {

        navigateToCoursePage.getPath().accept(driver);

        WebElement quizzesSectionLink = findElement(driver, By.linkText("Quizzes"));
        quizzesSectionLink.click();

        WebElement newQuizButton = findElement(driver, By.xpath("//div[@id='content']/div/div[2]/form/button"));
        newQuizButton.click();

        WebElement quizTitleField = findElement(driver, By.id("quiz_title"));
        quizTitleField.clear();
        quizTitleField.sendKeys(quiz.getName());

        quiz.setQuizEditPageUrl(driver.getCurrentUrl());

        explicitlyWait(driver, 2);

        /**
         * Here we have to add content into a tinyMCE iframe
         * https://stackoverflow.com/questions/21713345/cant-sendkeys-to-tinymce-with-selenium-webdriver
         */
        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent('"+quiz.getBody()+"')");

        WebElement saveQuizButton = findElement(driver, By.xpath("//div[@id='quiz_edit_actions']/div/div[2]/button[2]"));
        saveQuizButton.click();

        explicitlyWait(driver, 2);

        quiz.setQuizPageUrl(driver.getCurrentUrl());

    }

}
