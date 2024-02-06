package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.canvas.resources.Course;
import ca.ualberta.odobot.explorer.canvas.resources.Quiz;
import ca.ualberta.odobot.explorer.model.MultiPath;
import ca.ualberta.odobot.explorer.model.Operation;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static ca.ualberta.odobot.explorer.WebDriverUtils.*;

public class CreateQuiz extends Operation {
    private static final Logger log = LoggerFactory.getLogger(CreateQuiz.class);

    private Course course;

    private Quiz quiz;

    private MultiPath navigateToCoursePage = new MultiPath();

    public CreateQuiz(JsonObject config, Course course, Quiz quiz) {
        super(config);
        type=OperationType.CREATE;
        resource= Quiz.class;
        this.course = course;
        this.quiz = quiz;

        this.navigateToCoursePage.addPath(this::navOption2);
        this.navigateToCoursePage.setFallback(driver->driver.get(course.getCoursePageUrl()));
    }

    @Override
    protected void _execute(WebDriver driver) {

        navigateToCoursePage.getPath().accept(driver);

        WebElement quizzesSectionLink = driver.findElement(By.linkText(config.getString("quizzesSectionLinkText", "Quizzes")));
        quizzesSectionLink.click();

        explicitlyWait(driver, 3);

        WebElement newQuizButton = driver.findElement(By.xpath(config.getString("newQuizButtonXpath", "//div[@id='content']/div/div[2]/form/button")));
        explicitlyWaitUntil(driver, 5, d->newQuizButton.isDisplayed());
        newQuizButton.click();


        WebElement quizTitleField = driver.findElement(By.id(config.getString("quizTitleFieldId", "quiz_title")));
        quizTitleField.clear();
        quizTitleField.sendKeys(quiz.getName());

        quiz.setQuizEditPageUrl(driver.getCurrentUrl());

        explicitlyWait(driver, 3);

        /**
         * Here we have to add content into a tinyMCE iframe
         * https://stackoverflow.com/questions/21713345/cant-sendkeys-to-tinymce-with-selenium-webdriver
         */
        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent('"+quiz.getBody()+"')");


        WebElement saveQuizButton = driver.findElement(By.xpath(config.getString("saveQuizButtonXpath",  "//div[@id='quiz_edit_actions']/div/div[2]/button[2]")));
        saveQuizButton.click();

        explicitlyWait(driver, 3);

        quiz.setQuizPageUrl(driver.getCurrentUrl());

    }

    /**
     * Navigate to the corresponding course page by going through the course list in the global nav sidebar.
     * @param driver
     */
    private void navOption2(WebDriver driver){
        WebElement coursesSidebarLink = driver.findElement(By.id(config.getString("coursesSidebarLinkId", "global_nav_courses_link")));
        coursesSidebarLink.click();

        WebElement courseLink = driver.findElement(By.linkText(course.getName()));
        courseLink.click();
    }
}
