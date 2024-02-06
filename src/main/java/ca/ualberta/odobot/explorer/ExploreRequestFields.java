package ca.ualberta.odobot.explorer;

/**
 * @Author Alexandru Ianta
 *
 * Defines the fields expected for an /explore request in {@link ExplorerVerticle}.
 */
public enum ExploreRequestFields {
    ODOSIGHT_PATH("odoSightPath", String.class),

    ODOSIGHT_FLIGHT_PREFIX("odoSightFlightPrefix", String.class),
    WEB_APP_URL("webAppURL", String.class),
    ODOSIGHT_OPTIONS_LOGUI_USERNAME("logUIUsername", String.class),
    ODOSIGHT_OPTIONS_LOGUI_PASSWORD("logUIPassword", String.class),

    LOGUI_APPLICATION_ID("logUIApplicationId", String.class)

    ;


    ExploreRequestFields(String field, Class type){
        this.field = field;
        this.type = type;
    }
    public String field;
    public Class type;

}
