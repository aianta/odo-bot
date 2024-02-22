package ca.ualberta.odobot.explorer.canvas.resources;

import io.vertx.core.json.JsonObject;

public class Module extends BaseResource{

    private String name;

    private int id = -1;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }


    public void setName(String name) {
        this.name = name;
    }

    @Override
    public JsonObject getRuntimeData() {
        return new JsonObject()
                .put("id",getId() );
    }

    @Override
    public void setRuntimeData(JsonObject data) {
        if(data.containsKey("id")){
            setId(data.getInteger("id"));
        }
    }

    public JsonObject toJson(){
        JsonObject result = super.toJson()
                .put("name", getName());

        return result;
    }
}
