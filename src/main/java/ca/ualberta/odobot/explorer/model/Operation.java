package ca.ualberta.odobot.explorer.model;

import io.vertx.core.json.JsonObject;
import org.openqa.selenium.WebDriver;
import static ca.ualberta.odobot.explorer.WebDriverUtils.*;
import java.time.ZonedDateTime;

/**
 * @author Alexandru Ianta
 *
 * A class to manage a single operation to be executed on the test application.
 */
public abstract class Operation {

    protected JsonObject config;

    protected OperationType type;

    protected Class resource;

    boolean isCompleted = false;

    ZonedDateTime completedTimestamp;

    public enum OperationType{
        CREATE, EDIT, DELETE, INTRANSITIVE
    }

    public Operation(JsonObject config){
        this.config = config;
    }

    public void execute(WebDriver driver){
        _execute(driver);
        this.isCompleted = true;
        this.completedTimestamp = ZonedDateTime.now();
    }
    abstract protected void _execute(WebDriver driver);

}
