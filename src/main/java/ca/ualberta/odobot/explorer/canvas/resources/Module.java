package ca.ualberta.odobot.explorer.canvas.resources;

import io.vertx.core.json.JsonObject;

public class Module extends BaseResource{

    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public JsonObject getRuntimeData() {
        return null;
    }

    @Override
    public void setRuntimeData(JsonObject data) {

    }
}
