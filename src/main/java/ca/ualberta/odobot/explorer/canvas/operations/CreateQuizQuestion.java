package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.canvas.resources.Course;
import ca.ualberta.odobot.explorer.canvas.resources.Quiz;
import ca.ualberta.odobot.explorer.canvas.resources.QuizQuestion;
import ca.ualberta.odobot.explorer.model.MultiPath;
import ca.ualberta.odobot.explorer.model.Operation;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.explorer.WebDriverUtils.*;

public class CreateQuizQuestion extends Operation {
    private static final Logger log = LoggerFactory.getLogger(CreateQuizQuestion.class);

    private Course course;
    private Quiz quiz;
    private QuizQuestion question;
    private MultiPath navigateToQuizEditPage = new MultiPath();

    public CreateQuizQuestion(JsonObject config, Course course, Quiz quiz, QuizQuestion question) {
        super(config);
        type = OperationType.CREATE;
        resource = QuizQuestion.class;

        this.quiz = quiz;
        this.course = course;
        this.question = question;

        //Setup MutliPath(s)
        navigateToQuizEditPage.addPath(this::navOption1);
        navigateToQuizEditPage.addPath(this::navOption2);
        navigateToQuizEditPage.setFallback(driver->driver.get(quiz.getQuizEditPageUrl()));

    }


    @Override
    protected void _execute(WebDriver driver) {

        //If we're already on the quiz page, simply click the edit button.
        if(driver.getCurrentUrl().equals(quiz.getQuizPageUrl())){
            clickEditQuizButton(driver);
        }else{
            //Otherwise hard navigate to the edit quiz page using one of our predefined methods.
            navigateToQuizEditPage.getPath().accept(driver);
        }



        //Now we're on the edit page for the quiz, so we have to click the questions tab.
        WebElement questionsTab = driver.findElement(By.xpath("//a[contains(@href, '#questions_tab')]"));
        explicitlyWaitUntil(driver, 30, d-> ExpectedConditions.elementToBeClickable(questionsTab));
        questionsTab.click();
        questionsTab.click();
        explicitlyWait(driver, 4);

        //Get the questionId set before adding a new question
        Set<Integer> oldQuestionIds = getQuestionIdsOnPage(driver);


        //Then we click the add a new question button
        WebElement newQuestionButton = driver.findElement(By.cssSelector(".add_question_link:nth-child(1)"));
        explicitlyWaitUntil(driver,30, d->
                ExpectedConditions.and(
                        ExpectedConditions.elementToBeClickable(newQuestionButton),
                        ExpectedConditions.visibilityOf(newQuestionButton)
                ));
        click(driver, newQuestionButton);


        //Then we fill in the question name if on was specified.
        if(question.getName() != null){
            WebElement questionNameField = driver.findElement(By.name("question_name"));
            explicitlyWaitUntil(driver, 30, d->ExpectedConditions.elementToBeClickable(questionNameField));
            questionNameField.clear();
            questionNameField.sendKeys(question.getName());
        }

        //Then we configure the type of the question by selecting the question type drop down and selecting the correct option.
        WebElement questionTypeSelection = driver.findElement(By.name("question_type"));
        explicitlyWaitUntil(driver, 30, d->ExpectedConditions.elementToBeClickable(questionTypeSelection));
        questionTypeSelection.click();

        WebElement questionTypeOption = driver.findElement(By.xpath("//option[@value='"+question.getType().optionValue+"']"));
        questionTypeOption.click();

        /**
         *   Then we can fill in the question body itself.
         *   Here we have to add content into a tinyMCE iframe
         *   https://stackoverflow.com/questions/21713345/cant-sendkeys-to-tinymce-with-selenium-webdriver
         *   TODO: Keep an eye on this, it's unclear to me if 'question_content_0' will always be the correct editor
         */
        ((JavascriptExecutor)driver).executeScript("tinymce.EditorManager.get('question_content_0').setContent('"+question.getBody()+"')");


        //Now we handle questionType specific concerns.
        //TODO
//
//        switch (question.getType()){
//            case MULTIPLE_CHOICE ->
//        }

        //Then we click 'update question'
        WebElement updateQuestionButton = driver.findElement(By.xpath("//button[contains(.,'Update Question')]"));
        updateQuestionButton.click();


        //Let's grab the question's id for later.
        Set<Integer> newQuestionIds = getQuestionIdsOnPage(driver);

        //We have the set of question ids from before we create a new question, and after, therefore the new element ought to be the id of our new question.
        newQuestionIds.removeAll(oldQuestionIds);

        //We should always be left with one extra id.
        assert newQuestionIds.size() == 1;

        question.setId(newQuestionIds.iterator().next());
        log.info("Captured new question id as: {}", question.getId());



    }

    /**
     * One way to navigate to the edit quiz page.
     * Navigates to the course page via the sidebar link, then to quizzes, then click on
     * the quiz to open the quiz page, then click edit.
     * @param driver
     */
    private void navOption1(WebDriver driver){
        navigateToQuizzesSection(driver);
        explicitlyWait(driver, 4);
        WebElement quizPageLink = driver.findElement(By.xpath("//a[@href='"+quiz.getQuizPageUrl()+"']"));
        explicitlyWaitUntil(driver,30, d->ExpectedConditions.elementToBeClickable(quizPageLink));
        quizPageLink.click();

        clickEditQuizButton(driver);
    }

    /**
     * One way to navigate to the edit quiz page.
     * Navigates to the course page via the sidebar link, then to quizzes, then chooses edit from the quick actions drop down menu.
     * @param driver
     */
    private void navOption2(WebDriver driver){
        navigateToQuizzesSection(driver);

        WebElement quickActionDropDownMenu = driver.findElement(By.xpath("//div[@id='summary_quiz_"+quiz.getId()+"']/div/div[3]/div/button/i"));
        quickActionDropDownMenu.click();

        WebElement quickActionEditButton = driver.findElement(By.xpath("//a[@href='"+quiz.getQuizEditPageUrl()+"']"));
        quickActionEditButton.click();

    }

    /**
     * Navigate to the course page via the sidebar link, then to quizzes.
     * @param driver
     */
    private void navigateToQuizzesSection(WebDriver driver){
        WebElement coursesSidebarLink = driver.findElement(By.id("global_nav_courses_link"));
        explicitlyWaitUntil(driver, 5, d->ExpectedConditions.visibilityOf(coursesSidebarLink));
        coursesSidebarLink.click();

        WebElement courseLink = driver.findElement(By.linkText(course.getName()));
        courseLink.click();

        WebElement quizzesSectionLink = driver.findElement(By.linkText("Quizzes"));
        quizzesSectionLink.click();
    }

    private void clickEditQuizButton(WebDriver driver){
        WebElement editQuizButton = driver.findElement(By.className("edit_assignment_link"));
        editQuizButton.click();
    }

    private void click(WebDriver driver, WebElement element){
        try{
            element.click();
        }catch (ElementNotInteractableException e){
            explicitlyWait(driver, 3);
            click(driver, element);
        }
    }

    private Set<Integer> getQuestionIdsOnPage(WebDriver driver){

        List<Object> ids = (List<Object>) ((JavascriptExecutor)driver).executeScript("""
                //Returns the ids of existing questions on the page.
                
                return (function(){
                
                    //Find the container element for all the questions on the page
                    let questionsElement = document.getElementById('questions')
                    
                    //If the container is undefined, return an empty array.
                    if(!questionsElement){
                        return []
                    }
                    
                    //Setup a regex for identifying existing question divs.
                    let regex = new RegExp("question_[0-9]+")
                    
                    //Setup an array to store the question ids
                    let ids = []
                    
                    //Go through the child elements of the questions container
                    for(let child of questionsElement.children){
                        if(child !== undefined){
                        
                            //Each child is a 'question_holder', so to get to the question, we must go through the 'question_holder's children.
                            for (let innerChild of child.children){
                            
                                //If the question holder's child has an id that matches that of a question div.
                                if(regex.test(innerChild.id)){
                                
                                    //Get the id and add it to our id list.
                                    ids.push(innerChild.id.match(/[0-9]+/)[0])
                                }
                            }
                        }
                    }
                    return ids
                })()
                """);

        log.info("ids function result: {}", ids);

        return ids.stream().map(object->Integer.parseInt((String)object)).collect(Collectors.toSet());
    }
}
