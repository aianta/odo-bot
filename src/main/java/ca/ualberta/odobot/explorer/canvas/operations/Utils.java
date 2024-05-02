package ca.ualberta.odobot.explorer.canvas.operations;

import ca.ualberta.odobot.explorer.canvas.resources.Course;
import ca.ualberta.odobot.explorer.canvas.resources.Module;
import org.openqa.selenium.By;

public class Utils {

    public static final By byEditElement = By.cssSelector("a[class*='edit']");
    public static final By byDeleteElement = By.cssSelector("a[class*='delete']");

    private static final String MODULE_PATH_TEMPLATE  = "/courses/%s/modules/%s";

    public static By byDeleteModule(Course course, Module module){
        String path = MODULE_PATH_TEMPLATE.formatted(course.getId(), module.getId());
        return By.cssSelector("a[class*='delete'][class*='module'][href='%s']".formatted(path));
    }

    public static By byEditModule(Course course, Module module){
        String path = MODULE_PATH_TEMPLATE.formatted(course.getId(), module.getId());
        return By.cssSelector("a[class*='edit'][class*='module'][href='%s']".formatted(path));
    }

    public static By byDeleteCourse(Course course){
        String path = "/courses/%s/confirm_action?event=delete".formatted(course.getId());
        return By.cssSelector("a[href='%s']".formatted(path));
    }
}
