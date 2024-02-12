package ca.ualberta.odobot.explorer.model;

import io.vertx.core.json.JsonObject;
import org.openqa.selenium.WebDriver;
import static ca.ualberta.odobot.explorer.WebDriverUtils.*;
import java.time.ZonedDateTime;
import java.util.function.Consumer;

/**
 * @author Alexandru Ianta
 *
 * A class to manage a single operation to be executed on the test application.
 */
public class Operation {


    protected OperationType type;

    protected Class resource;

    boolean isCompleted = false;

    ZonedDateTime completedTimestamp;

    protected Consumer<WebDriver> executeMethod;

    public Operation( OperationType type) {
        this.type = type;
    }

    public enum OperationType{
        CREATE, EDIT, DELETE, INTRANSITIVE
    }


    public Consumer<WebDriver> getExecuteMethod() {
        return executeMethod;
    }

    public void setExecuteMethod(Consumer<WebDriver> executeMethod) {
        this.executeMethod = executeMethod;
    }

    public Operation( OperationType type, Class resource)
    {
        this.type = type;
        this.resource = resource;

    }

    public void execute(WebDriver driver){
        getExecuteMethod().accept(driver);
        this.isCompleted = true;
        this.completedTimestamp = ZonedDateTime.now();
    }


}
