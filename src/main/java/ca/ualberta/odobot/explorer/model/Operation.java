package ca.ualberta.odobot.explorer.model;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import org.openqa.selenium.WebDriver;



import static ca.ualberta.odobot.explorer.WebDriverUtils.*;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @author Alexandru Ianta
 *
 * A class to manage a single operation to be executed on the test application.
 */
public class Operation {

    private UUID id = UUID.randomUUID();

    protected OperationType type;

    private List<UUID> dependencies = new ArrayList<>();

    private JsonObject relatedIdentifiers = new JsonObject();

    protected Class resource;

    public Operation setResource(Class resource){
        this.resource = resource;
        return this;
    }

    boolean isCompleted = false;

    ZonedDateTime completedTimestamp;

    protected Consumer<WebDriver> executeMethod;

    public JsonObject getRelatedIdentifiers() {
        return relatedIdentifiers;
    }

    public OperationType getType() {
        return type;
    }

    public Operation(OperationType type) {
        this.type = type;
    }

    public Operation addRelatedIdentifier(String key, String value){
        relatedIdentifiers.put(key, value);
        return this;
    }

    public enum OperationType{
        CREATE, EDIT, DELETE, INTRANSITIVE
    }

    public Operation addDependency(JsonArray data){
        this.dependencies.addAll(
                data.stream().map(o->(String)o)
                        .map(UUID::fromString)
                        .collect(Collectors.toList())
        );
        return this;
    }
    public Operation addDependency(List<Operation> ops){
        ops.forEach(op->this.dependencies.add(op.id));
        return this;
    }
    public Operation addDependency(Operation ...ops){
        Arrays.stream(ops).forEach(op->
                this.dependencies.add(op.id));
        return this;
    }
    public Operation addDependency(Operation op){
        this.dependencies.add(op.id);
        return this;
    }

    public boolean isCompleted() {
        return isCompleted;
    }

    public void setRelatedIdentifiers(JsonObject relatedIdentifiers) {
        this.relatedIdentifiers = relatedIdentifiers;
    }

    public void setCompleted(boolean completed) {
        isCompleted = completed;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public Class getResource(){
        return resource;
    }

    public List<UUID> dependencies(){
        return this.dependencies;
    }

    public void execute(WebDriver driver){
        getExecuteMethod().accept(driver);
        this.isCompleted = true;
        this.completedTimestamp = ZonedDateTime.now();
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject();
        result.put("id", id.toString())
                .put("type", type.toString())
                .put("resource", resource.getName())
                .put("isCompleted", isCompleted)
                .put("relatedIdentifiers", relatedIdentifiers)
                .put("dependencies", dependencies.stream().map(uuid -> uuid.toString())
                        .collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
        ;

        if(completedTimestamp != null){
            result.put("completedTimestamp", completedTimestamp.toString());
        }

        return result;
    }



}
