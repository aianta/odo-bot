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
        return new JsonObject();
    }

    @Override
    public void setRuntimeData(JsonObject data) {
    }

    public JsonObject toJson(){
        JsonObject result = super.toJson()
                .put("name", getName());

        return result;
    }
}
