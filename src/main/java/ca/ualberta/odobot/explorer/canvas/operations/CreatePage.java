package ca.ualberta.odobot.explorer.canvas.operations;
import ca.ualberta.odobot.explorer.canvas.resources.Course;
import ca.ualberta.odobot.explorer.canvas.resources.Page;
import ca.ualberta.odobot.explorer.model.MultiPath;
import ca.ualberta.odobot.explorer.model.Operation;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static ca.ualberta.odobot.explorer.WebDriverUtils.*;
public class CreatePage extends Operation{

    private static final Logger log = LoggerFactory.getLogger(CreatePage.class);

    private Course course;
    private Page page;

    private MultiPath navigateToPagesSection;

    public CreatePage(JsonObject config, Course course, Page page) {
        super(config);
        type = OperationType.CREATE;
        resource = Page.class;

        this.course = course;
        this.page = page;

        this.navigateToPagesSection = new MultiPath();
        navigateToPagesSection.addPath(this::navOption1);
        navigateToPagesSection.setFallback(driver -> driver.get(course.getCoursePageUrl() + "/pages"));
    }

    @Override
    protected void _execute(WebDriver driver) {

        //Navigate to the pages screen
        navigateToPagesSection.getPath().accept(driver);


        //Click the new page button
        WebElement newPageButton = findElement(driver, By.xpath("//div/div/div/div/div/a"));
        newPageButton.click();

        //Enter the page title
        WebElement pageTitleField = findElement(driver, By.id("title"));
        pageTitleField.sendKeys(page.getTitle());

        //Enter the page body
        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent('"+page.getBody()+"')");

        //Click the save page button
        WebElement savePageButton = findElement(driver, By.className("submit"));
        savePageButton.click();


        //Find the page title element that will show up once the page has been successfully saved.
        findElement(driver, By.className("page-title"));

        page.setPageUrl(driver.getCurrentUrl());


    }

    private void navOption1(WebDriver driver){

        navigateToCoursePage1(driver, course);

        WebElement pagesSectionLink = driver.findElement(By.linkText("Pages"));
        pagesSectionLink.click();

    }

}
