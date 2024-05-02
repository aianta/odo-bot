package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.canvas.resources.Course;
import ca.ualberta.odobot.explorer.canvas.resources.Quiz;
import ca.ualberta.odobot.explorer.model.MultiPath;
import org.openqa.selenium.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static ca.ualberta.odobot.explorer.WebDriverUtils.*;
import static ca.ualberta.odobot.explorer.canvas.operations.CourseOperations.navToCourseViaDashboardCard;
import static ca.ualberta.odobot.explorer.canvas.operations.Utils.byEditElement;

public class QuizOperations {
    private static final Logger log = LoggerFactory.getLogger(QuizOperations.class);

    private Course course;

    private Quiz quiz;


    public QuizOperations(Course course, Quiz quiz) {
        this.course = course;
        this.quiz = quiz;

    }

    public void delete(WebDriver driver){
        //Navigate to the quiz page
        navToQuizzes(driver, course);
        click(driver, By.cssSelector("a[href='%s']".formatted(quiz.getQuizPageUrl())));


        //Find the drop-down menu button
        doubleClick(driver, By.xpath("//button[contains(.,'Manage')]"));

        //Find the delete option
        click(driver,By.className("delete_quiz_link"));

        //At this point an alerta should have popped up asking if we're really sure about deleting the quiz
        driver.switchTo().alert().accept();

    }

    public void edit(WebDriver driver){

        //Navigate to the quiz edit page
        //driver.get(quiz.getQuizEditPageUrl());
        navToQuizEditPage(driver, course, quiz);


        explicitlyWait(driver, 3);

        //Edit the quiz body
        if(quiz.getBody().isBlank()){
            quiz.setBody(quiz.getName() + " body");
        }
        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent(`"+ quiz.makeEdit(quiz.getBody())+"`)");

        //Click the save quiz button
        click(driver, By.className("save_quiz_button"));

    }

    public void create(WebDriver driver) {

        //Navigate to the course quizzes section
        //driver.get(course.getCoursePageUrl()+"/quizzes");
        navToQuizzes(driver, course);

        click(driver,By.xpath("//div[@id='content']/div/div[2]/form/button") );

        WebElement quizTitleField = findElement(driver, By.id("quiz_title"));
        quizTitleField.clear();
        quizTitleField.sendKeys(quiz.getName());

        quiz.setQuizEditPageUrl(driver.getCurrentUrl());

        explicitlyWait(driver, 2);

        /**
         * Here we have to add content into a tinyMCE iframe
         * https://stackoverflow.com/questions/21713345/cant-sendkeys-to-tinymce-with-selenium-webdriver
         */
        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent(`"+quiz.getBody()+"`)");

        click(driver, By.xpath("//div[@id='quiz_edit_actions']/div/div[2]/button[2]") );

        explicitlyWait(driver, 2);

        quiz.setQuizPageUrl(driver.getCurrentUrl());

    }

    public static void navToQuizEditPage(WebDriver driver, Course course, Quiz quiz){
        navToQuiz(driver, course, quiz);
        WebElement editLink = findElement(driver, byEditElement);
        click(driver, editLink);


    }

    public static void navToQuiz(WebDriver driver, Course course, Quiz quiz){
        navToQuizzes(driver, course);
        WebElement quizLink = findElement(driver, By.cssSelector("a[href*='%s']".formatted(quiz.getQuizPageUrl())));
        click(driver, quizLink);

    }

    private static void navToQuizzes(WebDriver driver, Course course){
        navToCourseViaDashboardCard(driver, course);

        WebElement quizzesLink = findElement(driver, By.linkText("Quizzes"));
        click(driver, quizzesLink);

    }

}
