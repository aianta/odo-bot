package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.WebDriverUtils;
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

        navigateToCoursePage.addPath(d->navigateToCoursePage1(d, course));
        navigateToCoursePage.addPath(d->navigateToCoursePage2(d, course));
        navigateToCoursePage.setFallback(d->d.get(course.getCoursePageUrl()));
    }

    @Override
    protected void _execute(WebDriver driver) {

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
