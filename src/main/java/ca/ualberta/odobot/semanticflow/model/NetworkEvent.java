package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.ZonedDateTime;

public class NetworkEvent extends AbstractArtifact implements TimelineEntity, FixedPoint{

    private static final Logger log = LoggerFactory.getLogger(NetworkEvent.class);

    private String serverIPAddress;

    private JsonObject response;
    private int statusCode;
    private String flightId;
    private JsonObject request;
    private String method;
    private URL url;

    private String userAgent;
    private String host;
    private String logUISessionId;

    public String getServerIPAddress() {
        return serverIPAddress;
    }

    public void setServerIPAddress(String serverIPAddress) {
        this.serverIPAddress = serverIPAddress;
    }

    public JsonObject getResponse() {
        return response;
    }

    public void setResponse(JsonObject response) {
        this.response = response;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }


    public String getFlightId() {
        return flightId;
    }

    public void setFlightId(String flightId) {
        this.flightId = flightId;
    }

    public JsonObject getRequest() {
        return request;
    }

    public void setRequest(JsonObject request) {
        this.request = request;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getUrl() {
        return url.toString();
    }

    public void setUrl(String url) {

        try{
            this.url = new URL(url);

        }catch (MalformedURLException e){
            log.error("Malformed URL: " + this.url);
        }

    }

    public String getPath(){
        return url.getPath().replaceAll("[0-9]+", "*");
    }

    public String getQuery(){
        return url.getQuery();
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getLogUISessionId() {
        return logUISessionId;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public void setLogUISessionId(String logUISessionId) {
        this.logUISessionId = logUISessionId;
    }

    @Override
    public int size() {
        return 1;
    }

    @Override
    public String symbol() {
        return "NET";
    }

    @Override
    public JsonObject toJson() {
        return new JsonObject()
                .put("serverIPAddress", getServerIPAddress())
                .put("host", getHost())
                .put("method", getMethod())
                .put("path", getPath())
                .put("query", getQuery())
                .put("statusCode", getStatusCode())
                .put("timestamp", getTimestamp().toString())
                .put("logUISessionId", getLogUISessionId())
                .put("flightId", getFlightId())
                .put("_activityLabel", getActivityLabel())
                .put("userAgent", getUserAgent());
    }

    @Override
    public long timestamp() {
        return getTimestamp().toInstant().toEpochMilli();
    }

    @Override
    public String getActivityLabel() {
        return getMethod() + " " + getPath();
    }
}
