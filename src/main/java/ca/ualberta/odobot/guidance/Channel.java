package ca.ualberta.odobot.guidance;

import java.util.Arrays;

/**
 * The logical channels of OdoX's socket to OdoBot. Every message names its channel, which decides the connection manager
 * that handles it.
 */
public enum Channel {
    /**
     * OdoX's events, transmission control and screenshots, handled by OdoX's background.
     */
    EVENT("event"),
    /**
     * Instructions for the page and the page's feedback, which OdoX's background passes to and from the page.
     */
    GUIDANCE("guidance");

    public final String name;

    Channel(String name){
        this.name = name;
    }

    /**
     * @return the channel with the given name, or null if there is none.
     */
    public static Channel fromName(String name){
        return Arrays.stream(values()).filter(channel -> channel.name.equals(name)).findFirst().orElse(null);
    }
}
