package ca.ualberta.odobot.explorer;

import io.vertx.core.json.JsonArray;

public enum PlanRequestFields implements RequestFields {

    COURSES("sourceCourses", JsonArray.class);


    PlanRequestFields(String field, Class type){
         this.type = type;
         this.field = field;
    }

    public String field;
    public Class type;

    @Override
    public String field() {
        return field;
    }

    @Override
    public Class type() {
        return type;
    }
}
