package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class NetworkEvent extends AbstractArtifact implements TimelineEntity, FixedPoint{

    private static final Logger log = LoggerFactory.getLogger(NetworkEvent.class);

    private static final DateTimeFormatter responseHeaderDateFormat = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz");

    private JsonObject requestObject;

    private JsonArray requestArray;
    private int requestId;
    private String documentUrl;
    private String type;

    private long millisecondTimestamp;
    private JsonObject responseObject;

    private JsonArray responseArray;

    private JsonObject requestHeaders;

    private JsonObject responseHeaders;

    private String flightId;

    private String method;
    private URL url;


    private String logUISessionId;

    public ZonedDateTime getResponseHeaderDate(){
        if (responseHeaders == null){
            log.warn("Could not get response header date because responseHeaders are null");
            return null;
        }

        if(!getResponseHeaders().containsKey("Date")){
            log.warn("Could not get response header date because responseHeaders is missing the 'Date' key.");
            return null;
        }

        String dateString = getResponseHeaders().getString("Date");

        return ZonedDateTime.parse(dateString, responseHeaderDateFormat);
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }


    public int getRequestId() {
        return requestId;
    }

    public void setRequestId(int requestId) {
        this.requestId = requestId;
    }

    public long getMillisecondTimestamp() {
        return millisecondTimestamp;
    }

    public void setMillisecondTimestamp(long millisecondTimestamp) {
        this.millisecondTimestamp = millisecondTimestamp;
    }

    public JsonObject getResponseObject() {
        return responseObject;
    }

    public void setResponseObject(JsonObject responseObject) {
        this.responseObject = responseObject;
    }


    public String getDocumentUrl() {
        return documentUrl;
    }

    public void setDocumentUrl(String documentUrl) {
        this.documentUrl = documentUrl;
    }

    public String getFlightId() {
        return flightId;
    }

    public void setFlightId(String flightId) {
        this.flightId = flightId;
    }

    public JsonArray getRequestArray() {
        return requestArray;
    }

    public void setRequestArray(JsonArray requestArray) {
        this.requestArray = requestArray;
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

    public JsonArray getResponseArray() {
        return responseArray;
    }

    public void setResponseArray(JsonArray responseArray) {
        this.responseArray = responseArray;
    }

    public JsonObject getRequestObject() {
        return requestObject;
    }

    public void setRequestObject(JsonObject requestObject) {
        this.requestObject = requestObject;
    }

    public String getLogUISessionId() {
        return logUISessionId;
    }

    public JsonObject getRequestHeaders() {
        return requestHeaders;
    }

    public void setRequestHeaders(JsonObject requestHeaders) {
        this.requestHeaders = requestHeaders;
    }

    public JsonObject getResponseHeaders() {
        return responseHeaders;
    }

    public void setResponseHeaders(JsonObject responseHeaders) {
        this.responseHeaders = responseHeaders;
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
         JsonObject result = new JsonObject()
                .put("method", getMethod())
                .put("path", getPath())
                .put("query", getQuery())
                .put("firefoxRequestId", getRequestId())
                .put("documentUrl", getDocumentUrl())
                .put("url", getUrl())
                .put("type", getType())
                .put("timestamp", getTimestamp().toString())
                .put("logUISessionId", getLogUISessionId())
                .put("flightId", getFlightId())
                .put("_activityLabel", getActivityLabel());

         if (getResponseObject() != null){
             result.put("response", getResponseObject());
         }

         if(getResponseArray() != null){
             result.put("response", getResponseArray());
         }

         if(getRequestObject() != null){
             result.put("request", getRequestObject());
         }

         if(getRequestArray() != null){
             result.put("request", getRequestArray());
         }

         if(getRequestHeaders() != null){
             result.put("requestHeaders", getRequestHeaders());
         }

         if(getResponseHeaders() != null){
             result.put("responseHeaders", getResponseHeaders());
         }

        return result;
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
