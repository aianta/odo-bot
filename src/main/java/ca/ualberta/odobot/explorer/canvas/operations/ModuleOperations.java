package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.canvas.resources.Course;
import ca.ualberta.odobot.explorer.canvas.resources.Module;
import ca.ualberta.odobot.explorer.model.MultiPath;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static ca.ualberta.odobot.explorer.WebDriverUtils.*;

public class ModuleOperations {
    private static final Logger log = LoggerFactory.getLogger(ModuleOperations.class);
    private Course course;
    private Module module;

    private MultiPath navigateToCoursePage = new MultiPath();

    public ModuleOperations( Course course, Module module) {
        this.course = course;
        this.module = module;

        navigateToCoursePage.addPath(d->navigateToCoursePage1(d, course));
        navigateToCoursePage.addPath(d->navigateToCoursePage2(d, course));
        navigateToCoursePage.setFallback(d->d.get(course.getCoursePageUrl()));
    }

    public void delete(WebDriver driver){
        //Go to the course page
        navigateToCoursePage.getPath().accept(driver);

        //Click the dropdown menu for our module
        WebElement dropDown = findElement(driver, By.cssSelector("button[aria-label='Manage "+module.getName()+"']"));
        dropDown.click();
        dropDown.click();

        //Click the delete option
        WebElement deleteOption = findElement(driver, By.linkText("Delete"));
        deleteOption.click();

        //At this stage an alert should come up to confirm the deletion
        driver.switchTo().alert().accept();

    }

    public void edit(WebDriver driver){

        //Go to the course page
        navigateToCoursePage.getPath().accept(driver);

        //Click the dropdown menu for our module
        WebElement dropDown = findElement(driver, By.cssSelector("button[aria-label='Manage "+module.getName()+"']"));
        dropDown.click();
        dropDown.click();

        //Click the edit option from the drop-down menu
        WebElement editOption = findElement(driver, By.linkText("Edit"));
        editOption.click();

        //Edit the module name
        WebElement moduleNameField = findElement(driver, By.id("context_module_name"));

        String newModuleName = module.makeEdit(module.getName());
        module.setName(newModuleName);
        moduleNameField.clear();
        moduleNameField.sendKeys(newModuleName);

        //Click the update module button
        WebElement updateModuleButton = findElement(driver, By.xpath("//button[contains(.,'Update Module')]"));
        updateModuleButton.click();

    }

    public void create(WebDriver driver) {

        navigateToCoursePage.getPath().accept(driver);

        WebElement addModuleLink = findElement(driver, By.className("add_module_link"));
        addModuleLink.click();
        try{
            addModuleLink.click();
        }catch (Exception e){
            log.warn("double click warning");
        }
        WebElement moduleNameField = findElement(driver, By.id("context_module_name"));
        moduleNameField.sendKeys(module.getName());

        WebElement addModuleButton = findElement(driver, By.xpath("//button[contains(.,'Add Module')]"));
        addModuleButton.click();

        findElement(driver, By.xpath("//span[contains(.,' "+module.getName()+"')]"));

    }




}
