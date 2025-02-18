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
import static ca.ualberta.odobot.explorer.canvas.operations.CourseOperations.navToCourseViaDashboardCard;
import static ca.ualberta.odobot.explorer.canvas.operations.Utils.byDeleteElement;

public class PageOperations{

    private static final Logger log = LoggerFactory.getLogger(PageOperations.class);

    private Course course;
    private Page page;


    public PageOperations( Course course, Page page) {
        this.course = course;
        this.page = page;

    }

    public void create(WebDriver driver){
        //Navigate to the pages screen
        //navigateToPagesSection.getPath().accept(driver);

        //driver.get(course.getCoursePageUrl() + "/pages");
        navToPages(driver, course);

        //Click the new page button
        click(driver,By.xpath("//div/div/div/div/div/a"));

        //Enter the page title
        WebElement pageTitleField = findElement(driver, By.id("title"));
        pageTitleField.sendKeys(page.getTitle());

        //Enter the page body
        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent(`"+page.getBody()+"`)");

        //Click the save page button
        click(driver,By.className("submit") );


        //Find the page title element that will show up once the page has been successfully saved.
        findElement(driver, By.className("page-title"));

        page.setPageUrl(driver.getCurrentUrl());
    }

    public void edit(WebDriver driver){
        //Navigate to the page of interest

        navToPages(driver, course);

        click(driver, By.cssSelector("a[href*='%s']".formatted(page.getPageUrl())));
//
//        driver.get(page.getPageUrl());

        //Click the edit button
        click(driver,By.xpath("//span[contains(.,' Edit')]"));

        explicitlyWait(driver, 3);

        //Find the input element for the page title
        String newTitle = "Modified - " + page.getTitle();
        WebElement pageTitleField = findElement(driver, By.id("title"));
        pageTitleField.clear(); //Clear existing title.
        pageTitleField.sendKeys(newTitle);
        explicitlyWait(driver, 3);

        String newContent = page.makeEdit(page.getBody());

        //Update the page body
        ((JavascriptExecutor)driver).executeScript("tinyMCE.activeEditor.setContent(`"+newContent+"`);");

        //Save the changes
        click(driver,By.className("submit") );

        explicitlyWait(driver, 1);

        //Find the page title element that will show up once the page has been successfully saved.
        findElement(driver, By.className("page-title"));

        //Update the page URL since we changed the page title.
        page.setPageUrl(driver.getCurrentUrl());
        //Update the page title since we changed it.
        page.setTitle(newTitle);
    }

    public void delete(WebDriver driver){

        //Navigate to the pages section of the course
        navToPages(driver, course);
        //Navigate to the page of interest
        click(driver, By.cssSelector("a[href='%s']".formatted(page.getPageUrl())));


        //Click the drop-down menu beside the edit button
        click(driver, By.xpath("//div[@id='wiki_page_show']/div/div/div/div[2]/div[3]/div/a"));

        //click delete button
        click(driver, byDeleteElement);

        //Click confirm delete button
        click(driver, By.cssSelector(".btn-danger > .ui-button-text"));

    }

    private void navToPages(WebDriver driver, Course course){
        navToCourseViaDashboardCard(driver, course);

        WebElement pagesLink = findElement(driver, By.linkText("Pages"));
        click(driver, pagesLink);

    }


}
