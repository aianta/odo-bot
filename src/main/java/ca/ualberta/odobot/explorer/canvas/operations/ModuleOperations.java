package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.canvas.resources.Course;
import ca.ualberta.odobot.explorer.canvas.resources.Module;
import ca.ualberta.odobot.explorer.model.MultiPath;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
        do{
            driver.get(course.getCoursePageUrl());
            explicitlyWait(driver, 2);
        }
        while(!driver.getCurrentUrl().equals(course.getCoursePageUrl()));

        //Click the dropdown menu for our module
        click(driver, getManageElement(driver));

        //Click the delete option
        click(driver, By.linkText("Delete"));

        //At this stage an alert should come up to confirm the deletion
        driver.switchTo().alert().accept();

    }

    public void edit(WebDriver driver){

        //Go to the course page
        do {
            driver.get(course.getCoursePageUrl());
            explicitlyWait(driver, 2);
        }while (!driver.getCurrentUrl().equals(course.getCoursePageUrl()));

        //Click the dropdown menu for our module
        click(driver,getManageElement(driver));

        //Click the edit option from the drop-down menu
        click(driver,By.linkText("Edit"));

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

        //Get the moduleId set before creating the new module
        Set<Integer> oldModuleIds = getModuleIdsOnPage(driver);

        //Then we create our new module
        doubleClick(driver,By.className("add_module_link") );

        WebElement moduleNameField = findElement(driver, By.id("context_module_name"));
        moduleNameField.sendKeys(module.getName());


        click(driver,By.xpath("//button[contains(.,'Add Module')]") );

        Set<Integer> newModuleIds = getModuleIdsOnPage(driver);
        newModuleIds.removeAll(oldModuleIds);

        //We should always be left with one extra id
        assert newModuleIds.size() == 1;

        module.setId(newModuleIds.iterator().next());
        log.info("Capture new module id as: {}", module.getId());

        findElement(driver, By.xpath("//span[contains(.,' "+module.getName()+"')]"));

    }


    private WebElement getManageElement(WebDriver driver){
        return (WebElement) ((JavascriptExecutor)driver).executeScript("return document.querySelector('#context_module_"+module.getId()+" button.Button--icon-action.al-trigger')");
    }

    private Set<Integer> getModuleIdsOnPage(WebDriver driver){

        List<Object> ids = (List<Object>)((JavascriptExecutor)driver).executeScript("""
                //Returns the ids of existing modules on the page.
                
                return (function(){
                
                    //Find the container element for all the modules on the page
                    let modulesElement = document.getElementById('context_modules')
                    
                    //If the container is undefined, return an empty array.
                    if(!modulesElement){
                        return []
                    }
                    
                    //Setup a regex for identifying existing question divs.
                    let regex = new RegExp("context_module_[0-9]+")
                    
                    //Setup an array to store the question ids
                    let ids = []
                    
                    //Go through the child elements of the modules container
                    for(let child of modulesElement.children){
                        if(child !== undefined){
                            if(regex.test(child.id)){
                            
                                //Get the id and add it to our id list.
                                ids.push(child.id.match(/[0-9]+/)[0])
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
