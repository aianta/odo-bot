package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.canvas.resources.Course;
import ca.ualberta.odobot.explorer.canvas.resources.Quiz;
import ca.ualberta.odobot.explorer.canvas.resources.QuizQuestion;
import ca.ualberta.odobot.explorer.model.MultiPath;
import ca.ualberta.odobot.explorer.model.Operation;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.*;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.explorer.WebDriverUtils.*;

public class QuizQuestionOperations {
    private static final Logger log = LoggerFactory.getLogger(QuizQuestionOperations.class);

    private Course course;
    private Quiz quiz;
    private QuizQuestion question;


    public QuizQuestionOperations( Course course, Quiz quiz, QuizQuestion question) {

        this.quiz = quiz;
        this.course = course;
        this.question = question;

    }

    public void delete(WebDriver driver){


        driver.get(quiz.getQuizEditPageUrl());

        //Now we're on the edit page for the quiz, so we have to click the questions tab.
        doubleClick(driver, By.xpath("//a[contains(@href, '#questions_tab')]"), d->d.get(quiz.getQuizEditPageUrl()));
        explicitlyWait(driver, 3);

        //Get the question element so we can hover over it to reveal the edit button
        WebElement questionElement = findElement(driver, By.id("question_"+ question.getId()));

        Actions action = new Actions(driver);
        action.moveToElement(questionElement).perform();

        //Click the delete button for this specific quiz question
        click(driver, By.cssSelector("#question_"+question.getId()+" .icon-end"));


        String windowHandle = driver.getWindowHandle();
        //At this point an alert should come up to ask us if we're sure we want to delete the question, so lets accept it.
        driver.switchTo().alert().accept();
        driver.switchTo().window(windowHandle); //Move back to window after alert.

    }

    public void edit(WebDriver driver){

        driver.get(quiz.getQuizEditPageUrl());

        //Now we're on the edit page for the quiz, so we have to click the questions tab.
        doubleClick(driver,By.xpath("//a[contains(@href, '#questions_tab')]"), d->d.get(quiz.getQuizEditPageUrl()));

        explicitlyWait(driver, 3);

        //Get the question element so we can hover over it to reveal the edit button
        WebElement questionElement = findElement(driver, By.id("question_"+ question.getId()));

        Actions action = new Actions(driver);
        action.moveToElement(questionElement).perform();

        //Click the edit button for this specific quiz question
        click(driver, By.cssSelector("#question_"+question.getId()+" .icon-edit"));

        //Update the question content
        ((JavascriptExecutor)driver).executeScript("tinymce.EditorManager.get('question_content_0').setContent(`" + question.makeEdit(question.getBody()) + "`)");

        //Click the update question button
        click(driver, By.xpath("//button[contains(.,'Update Question')]"));


    }

    public void create(WebDriver driver) {

        driver.get(quiz.getQuizEditPageUrl());

        //Now we're on the edit page for the quiz, so we have to click the questions tab.
        doubleClick(driver, By.xpath("//a[contains(@href, '#questions_tab')]"), d->d.get(quiz.getQuizEditPageUrl()));


        //Get the questionId set before adding a new question
        Set<Integer> oldQuestionIds = getQuestionIdsOnPage(driver);


        //Then we click the add a new question button
        click(driver, By.cssSelector(".add_question_link:nth-child(1)"));

        //Then we fill in the question name if on was specified.
        if(question.getName() != null){
            WebElement questionNameField = findElement(driver, By.name("question_name"));
            explicitlyWaitUntil(driver, 30, d-> ExpectedConditions.elementToBeClickable(questionNameField));
            questionNameField.clear();
            questionNameField.sendKeys(question.getName());
        }

        //Then we configure the type of the question by selecting the question type drop down and selecting the correct option.
        click(driver, By.name("question_type"));

        click(driver,By.xpath("//option[@value='"+question.getType().optionValue+"']"));

        explicitlyWait(driver, 2);
        /**
         *   Then we can fill in the question body itself.
         *   Here we have to add content into a tinyMCE iframe
         *   https://stackoverflow.com/questions/21713345/cant-sendkeys-to-tinymce-with-selenium-webdriver
         *   TODO: Keep an eye on this, it's unclear to me if 'question_content_0' will always be the correct editor
         */
        ((JavascriptExecutor)driver).executeScript("tinymce.EditorManager.get('question_content_0').setContent(`"+question.getBody()+"`)");


        //Then we click 'update question'
        click(driver, By.xpath("//button[contains(.,'Update Question')]"));


        //Let's grab the question's id for later.
        Set<Integer> newQuestionIds = getQuestionIdsOnPage(driver);

        //We have the set of question ids from before we create a new question, and after, therefore the new element ought to be the id of our new question.
        newQuestionIds.removeAll(oldQuestionIds);

        //We should always be left with one extra id.
        assert newQuestionIds.size() == 1;

        question.setId(newQuestionIds.iterator().next());
        log.info("Captured new question id as: {}", question.getId());



    }


    private void clickEditQuizButton(WebDriver driver){
        WebElement editQuizButton = findElement(driver, By.className("edit_assignment_link"));
        editQuizButton.click();
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
