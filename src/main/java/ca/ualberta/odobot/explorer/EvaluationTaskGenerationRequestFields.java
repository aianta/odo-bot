package ca.ualberta.odobot.explorer;

import io.vertx.core.json.JsonArray;

public enum EvaluationTaskGenerationRequestFields implements RequestFields {

    COURSES("courses", JsonArray.class),
    LOGIN_TEMPLATES("login_templates", JsonArray.class),
    TARGET_APP_URL("appUrl", String.class),
    TARGET_APP_NAME("appName", String.class),
    TARGET_APP_USERNAME("username", String.class),
    TARGET_APP_PASSWORD("password", String.class),

    STARTING_USER_LOCATION("startingUserLocation", String.class),

    NUM_INSTANCES_PER_TASK("instancesPerTask", Integer.class),
    TASKS("tasks", JsonArray.class);

    EvaluationTaskGenerationRequestFields(String field, Class type){
        this.type = type;
        this.field = field;
    }

    public String field;
    public Class type;


    public String field() {
        return field;
    }

    @Override
    public Class type() {
        return type;
    }

}
