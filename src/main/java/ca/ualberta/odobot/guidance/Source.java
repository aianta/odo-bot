package ca.ualberta.odobot.guidance;

import java.util.Arrays;

/**
 * Enumerates the possible logical sources for {@link WebSocketConnection}
 */
public enum Source {
    GUIDANCE_SOCKET("GuidanceSocket"),
    EVENT_SOCKET("EventSocket"),
    CONTROL_SOCKET("ControlSocket");

    String name;

    Source(String name){
        this.name = name;
    }

    static Source getSourceByName(String name){
        return Arrays.stream(values()).filter(source -> source.name.equals(name)).findFirst().get();
    }
}
