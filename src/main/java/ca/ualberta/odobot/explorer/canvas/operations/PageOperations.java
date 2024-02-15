package ca.ualberta.odobot.explorer.canvas.operations;
import ca.ualberta.odobot.explorer.canvas.resources.Course;
import ca.ualberta.odobot.explorer.canvas.resources.Page;
import ca.ualberta.odobot.explorer.model.MultiPath;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static ca.ualberta.odobot.explorer.WebDriverUtils.*;
public class PageOperations{

    private static final Logger log = LoggerFactory.getLogger(PageOperations.class);

    private Course course;
    private Page page;

    private MultiPath navigateToPagesSection;

    public PageOperations( Course course, Page page) {
        this.course = course;
        this.page = page;

        this.navigateToPagesSection = new MultiPath();
        navigateToPagesSection.addPath(this::navOption1);
        navigateToPagesSection.setFallback(driver -> driver.get(course.getCoursePageUrl() + "/pages"));
    }

    public void create(WebDriver driver){
        //Navigate to the pages screen
        navigateToPagesSection.getPath().accept(driver);


        //Click the new page button
        WebElement newPageButton = findElement(driver, By.xpath("//div/div/div/div/div/a"));
        newPageButton.click();

        //Enter the page title
        WebElement pageTitleField = findElement(driver, By.id("title"));
        pageTitleField.sendKeys(page.getTitle());

        //Enter the page body
        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent(`"+page.getBody()+"`)");

        //Click the save page button
        WebElement savePageButton = findElement(driver, By.className("submit"));
        savePageButton.click();


        //Find the page title element that will show up once the page has been successfully saved.
        findElement(driver, By.className("page-title"));

        page.setPageUrl(driver.getCurrentUrl());
    }

    public void edit(WebDriver driver){
        //Navigate to the pages screen
        navigateToPagesSection.getPath().accept(driver);

        //Click on the page of interest
        WebElement pageLink = findElement(driver, By.linkText(page.getTitle()));
        pageLink.click();

        WebElement editButton = findElement(driver, By.xpath("//span[contains(.,' Edit')]"));
        editButton.click();

        explicitlyWait(driver, 3);

        String newContent = page.makeEdit(page.getBody());

        //Update the page body
        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent(`"+newContent+"`);");

        //Save the changes
        WebElement savePageButton = findElement(driver, By.className("submit"));
        savePageButton.click();

        //Find the page title element that will show up once the page has been successfully saved.
        findElement(driver, By.className("page-title"));


    }

    public void delete(WebDriver driver){

        //Navigate to the pages screen.
        navigateToPagesSection.getPath().accept(driver);

        //Click on the page of interest
        WebElement pageLink = findElement(driver, By.linkText(page.getTitle()));
        pageLink.click();


        //Click the drop-down menu beside the edit button
        WebElement dropDown = findElement(driver, By.xpath("//div[@id='wiki_page_show']/div/div/div/div[2]/div[3]/div/a"));
        dropDown.click();

        WebElement deleteOption = findElement(driver, By.linkText("Delete"));
        deleteOption.click();

        WebElement confirmDeleteButton = findElement(driver, By.cssSelector(".btn-danger > .ui-button-text"));
        confirmDeleteButton.click();

    }


    private void navOption1(WebDriver driver){

        navigateToCoursePage1(driver, course);

        WebElement pagesSectionLink = driver.findElement(By.linkText("Pages"));
        pagesSectionLink.click();

    }

}
