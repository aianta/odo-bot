package ca.ualberta.odobot.guidance;

import java.util.function.BooleanSupplier;

/**
 * One channel's view of OdoX's socket, see {@link Channel}. Its connection manager sends through it.
 */
public class ChannelConnection {

    private final Channel channel;
    private final WebSocketConnection socket;
    private final BooleanSupplier open;

    /**
     * @param socket the shared socket, or null for a connection without a socket behind it.
     * @param open whether the channel is open while the socket is connected, e.g. the guidance channel only while
     *             OdoX has a page ready.
     */
    public ChannelConnection(Channel channel, WebSocketConnection socket, BooleanSupplier open){
        this.channel = channel;
        this.socket = socket;
        this.open = open;
    }

    public Channel channel(){
        return channel;
    }

    /**
     * @return whether messages can go out now. If not, the manager queues them until the channel opens.
     */
    public boolean isConnected(){
        return socket != null && socket.isConnected() && open.getAsBoolean();
    }

    public void send(String data){
        socket.send(data);
    }
}
