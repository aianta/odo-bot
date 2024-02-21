package ca.ualberta.odobot.explorer;

import io.vertx.core.json.JsonArray;

/**
 * @Author Alexandru Ianta
 *
 * Defines the fields expected for an /explore request in {@link ExplorerVerticle}.
 */
public enum ExploreRequestFields implements RequestFields{
    ODOSIGHT_PATH("odoSightPath", String.class),

    ODOSIGHT_FLIGHT_PREFIX("odoSightFlightPrefix", String.class),
    WEB_APP_URL("webAppURL", String.class),
    ODOSIGHT_OPTIONS_LOGUI_USERNAME("logUIUsername", String.class),
    ODOSIGHT_OPTIONS_LOGUI_PASSWORD("logUIPassword", String.class),

    LOGUI_APPLICATION_ID("logUIApplicationId", String.class),

    CANVAS_USERNAME("canvasUsername", String.class),

    CANVAS_PASSWORD("canvasPassword", String.class),

    STARTING_URL("startingURL", String.class),

    MANIFEST("manifest", JsonArray.class),

    SAVE_PATH("savePath", String.class),

    ERROR_PATH("failureInfoPath", String.class),

    COURSES("sourceCourses", JsonArray.class);

    ;


    ExploreRequestFields(String field, Class type){
        this.field = field;
        this.type = type;
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
