package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.canvas.resources.Course;
import ca.ualberta.odobot.explorer.canvas.resources.Module;
import ca.ualberta.odobot.explorer.model.MultiPath;
import ca.ualberta.odobot.explorer.model.Operation;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static ca.ualberta.odobot.explorer.WebDriverUtils.*;

public class CreateModule extends Operation {
    private static final Logger log = LoggerFactory.getLogger(CreateModule.class);
    private Course course;
    private Module module;

    private MultiPath navigateToCoursePage = new MultiPath();

    public CreateModule(JsonObject config, Course course, Module module) {
        super(config);
        type = OperationType.CREATE;
        resource = Module.class;
        this.course = course;
        this.module = module;

        navigateToCoursePage.addPath(this::navOption2);
        navigateToCoursePage.addPath(this::navOption1);
        navigateToCoursePage.setFallback(this::navOption1);
    }

    @Override
    protected void _execute(WebDriver driver) {

        navigateToCoursePage.getPath().accept(driver);

        WebElement addModuleLink = driver.findElement(By.className(config.getString("addModuleLinkCSSClass", "add_module_link")));
        addModuleLink.click();
        addModuleLink.click();

        WebElement moduleNameField = driver.findElement(By.id(config.getString("moduleNameFieldId", "context_module_name")));
        moduleNameField.sendKeys(module.getName());

        WebElement addModuleButton = driver.findElement(By.xpath(config.getString("addModuleButtonXpath", "//button[contains(.,'Add Module')]")));
        addModuleButton.click();

        WebElement moduleSpan = driver.findElement(By.xpath("//span[contains(.,' "+module.getName()+"')]"));
        explicitlyWaitUntil(driver, 5, d->moduleSpan.isDisplayed());

    }

    /**
     * Navigate to the corresponding course page by directly getting the course url.
     * @param driver
     */
    private void navOption1(WebDriver driver){
        driver.get(course.getCoursePageUrl());
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

    /**
     * Navigate to the corresponding course page by going through the dashboard.
     * @param driver
     */
    private void navOption3(WebDriver driver){
        WebElement dashboardLink = driver.findElement(By.id(config.getString("dashboardSidebarLinkId", "global_nav_dashboard_link")));
        dashboardLink.click();

        //WebElement courseCardLink = driver.findElement(By.xpath());
    }


}
