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

        explicitlyWait(driver, 1);

        //Click the new page button
        WebElement newPageButton = driver.findElement(By.xpath("//div/div/div/div/div/a"));
        explicitlyWaitUntil(driver,30, d->ExpectedConditions.elementToBeClickable(newPageButton));
        newPageButton.click();

        //Enter the page title
        WebElement pageTitleField = driver.findElement(By.id("title"));
        pageTitleField.sendKeys(page.getTitle());

        //Enter the page body
        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent('"+page.getBody()+"')");

        //Click the save page button
        WebElement savePageButton = driver.findElement(By.className("submit"));
        savePageButton.click();

        explicitlyWait(driver, 1);

        //Find the page title element that will show up once the page has been successfully saved.
        WebElement pageTitle = driver.findElement(By.className("page-title"));
        explicitlyWaitUntil(driver, 30, d->ExpectedConditions.visibilityOf(pageTitle));

        page.setPageUrl(driver.getCurrentUrl());


    }

    private void navOption1(WebDriver driver){

        WebElement coursesSidebarLink = driver.findElement(By.id("global_nav_courses_link"));
        explicitlyWaitUntil(driver, 30, d-> ExpectedConditions.visibilityOf(coursesSidebarLink));
        coursesSidebarLink.click();

        WebElement courseLink = driver.findElement(By.linkText(course.getName()));
        courseLink.click();

        WebElement pagesSectionLink = driver.findElement(By.linkText("Pages"));
        pagesSectionLink.click();

    }

}
