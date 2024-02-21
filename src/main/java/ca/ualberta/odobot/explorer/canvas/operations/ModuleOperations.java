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

    public ModuleOperations( Course course, Module module) {
        this.course = course;
        this.module = module;

    }

    public void delete(WebDriver driver){
        //Go to the course page
        driver.get(course.getCoursePageUrl());

        //Click the dropdown menu for our module
        doubleClick(driver, By.cssSelector("button[aria-label='Manage "+module.getName()+"']"));

        //Click the delete option
        click(driver, By.linkText("Delete"));

        //At this stage an alert should come up to confirm the deletion
        driver.switchTo().alert().accept();

    }

    public void edit(WebDriver driver){

        //Go to the course page
        driver.get(course.getCoursePageUrl());

        //Click the dropdown menu for our module
        doubleClick(driver,By.cssSelector("button[aria-label='Manage "+module.getName()+"']") );

        //Click the edit option from the drop-down menu
        click(driver,By.linkText("Edit") );

        //Edit the module name
        WebElement moduleNameField = findElement(driver, By.id("context_module_name"));

        String newModuleName = module.makeEdit(module.getName());
        module.setName(newModuleName);
        moduleNameField.clear();
        moduleNameField.sendKeys(newModuleName);

        //Click the update module button
        click(driver, By.xpath("//button[contains(.,'Update Module')]"));

    }

    public void create(WebDriver driver) {

        //Go to the course page
        driver.get(course.getCoursePageUrl());

        doubleClick(driver,By.className("add_module_link") );

        WebElement moduleNameField = findElement(driver, By.id("context_module_name"));
        moduleNameField.sendKeys(module.getName());


        click(driver,By.xpath("//button[contains(.,'Add Module')]") );

        findElement(driver, By.xpath("//span[contains(.,' "+module.getName()+"')]"));

    }




}
