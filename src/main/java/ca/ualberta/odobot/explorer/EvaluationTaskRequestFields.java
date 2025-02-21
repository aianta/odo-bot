package ca.ualberta.odobot.explorer;

import io.vertx.core.json.JsonArray;

public enum EvaluationTaskRequestFields implements RequestFields {

    ODOSIGHT_PATH("odoSightPath", String.class),
    WEB_APP_URL("webAppURL", String.class),

    ODOX_OPTIONS_LOGUI_USERNAME("logUIUsername", String.class),

    ODOX_OPTIONS_LOGUI_PASSWORD("logUIPassword", String.class),
    TASKS("tasks", JsonArray.class);

    EvaluationTaskRequestFields(String field, Class type){
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
