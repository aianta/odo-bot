package ca.ualberta.odobot.guidance;

import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.JksOptions;

/**
 * The server options OdoX's websockets need: TLS with OdoBot's certificate, and frames large enough for DOM snapshots.
 */
public final class OdoXServerOptions {

    /**
     * The largest websocket frame and message OdoX may send. Its DOM effects carry whole pages.
     */
    public static final int MAX_WEBSOCKET_BYTES = 10000000; //10MB

    private OdoXServerOptions(){}

    /**
     * Apply OdoX's settings to the given options.
     *
     * @param guidanceConfig the guidance configuration (config/guidance.yaml), which names the keystore.
     */
    public static HttpServerOptions apply(HttpServerOptions options, JsonObject guidanceConfig){
        JksOptions jksOptions = new JksOptions()
                .setPath(guidanceConfig.getString("jksPath"))
                .setAlias("odobot-server")
                .setPassword(guidanceConfig.getString("jksPassword"));

        return options
                .setSsl(true)
                .setKeyStoreOptions(jksOptions)
                .setMaxWebSocketFrameSize(MAX_WEBSOCKET_BYTES)
                .setMaxWebSocketMessageSize(MAX_WEBSOCKET_BYTES);
    }

    /**
     * @return the options of a task's own listener: OdoX's settings, on a port the OS picks.
     */
    public static HttpServerOptions forTask(JsonObject guidanceConfig){
        return apply(new HttpServerOptions().setHost("0.0.0.0").setPort(0), guidanceConfig);
    }
}
