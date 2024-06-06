package ca.ualberta.odobot.guidance.connectionmanagers;

import ca.ualberta.odobot.guidance.WebSocketConnection;

public interface ConnectionManager {

    void updateConnection(WebSocketConnection connection);

}
