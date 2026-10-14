package ca.ualberta.odobot.task;

import ca.ualberta.odobot.task.webarena.TaskAuth;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything outside OdoBot that one task touches. OdoBot does not manage environments: an external manager makes
 * sure they are up before it submits the tasks, and names each task's environment in the task's {@code environment}
 * object. Fields a task leaves out fall back to the experiment request's, so older requests keep working.
 *
 * @param gridUrl the Selenium grid of the task's Firefox, or null for a local Firefox.
 * @param startUrl the page the browser opens. The charted agent also localizes from it.
 * @param targetHosts the origins whose network events OdoX records, or ["*"] for all.
 * @param sites named base URLs of the environment's apps, for evaluators that query a backend.
 * @param logUiHost OdoX's options page requires LogUI settings, though nothing is sent to LogUI during evaluation.
 *                  Required, like the username and password.
 * @param auth how the agent gets logged in to the sites: by itself, or before it starts, as in WebArena.
 */
public record TaskEnvironment(
        String gridUrl,
        String startUrl,
        List<String> targetHosts,
        Map<String, String> sites,
        String logUiHost,
        String logUiUsername,
        String logUiPassword,
        TaskAuth auth
) {

    /**
     * Resolve a task's environment: each field from the task's {@code environment} object, else from the experiment
     * request, else its default.
     *
     * <p>{@code startUrl} is also read from the task itself, where older task files put it. The experiment-level
     * fallback is {@code webAppURL}. A task's {@code userLocation} is never used, since task files often hold host-side
     * addresses there that the browser cannot reach.</p>
     *
     * @throws IllegalArgumentException if the task ends up without a start page, or a field has the wrong type.
     */
    public static TaskEnvironment resolve(JsonObject task, JsonObject experimentRequest){
        Object rawEnvironment = task.getValue("environment");
        if(rawEnvironment != null && !(rawEnvironment instanceof JsonObject)){
            throw new IllegalArgumentException("'environment' must be an object.");
        }
        JsonObject environment = rawEnvironment == null? new JsonObject() : (JsonObject) rawEnvironment;

        String startUrl = string(environment, "startUrl");
        if(startUrl == null){
            startUrl = string(task, "startUrl");
        }
        if(startUrl == null){
            startUrl = string(experimentRequest, "webAppURL");
        }
        if(startUrl == null){
            throw new IllegalArgumentException("no start page: set 'environment.startUrl' on the task, or 'webAppURL' on the experiment.");
        }

        List<String> targetHosts = hosts(environment.containsKey("targetHosts")? environment : experimentRequest);
        if(targetHosts == null){
            targetHosts = List.of(origin(startUrl));
        }

        return new TaskEnvironment(
                firstOf(environment, experimentRequest, "firefoxDockerGridURL"),
                startUrl,
                List.copyOf(targetHosts),
                sites(environment),
                required(environment, experimentRequest, "logUIHost"),
                required(environment, experimentRequest, "logUIUsername"),
                required(environment, experimentRequest, "logUIPassword"),
                TaskAuth.fromJson(environment.getValue("auth"))
        );
    }

    /**
     * @return the environment as saved with the task's artifacts. The LogUI password and the auth passwords are left out.
     */
    public JsonObject toJson(){
        JsonObject sitesJson = new JsonObject();
        sites.forEach(sitesJson::put);
        return new JsonObject()
                .put("firefoxDockerGridURL", gridUrl)
                .put("startUrl", startUrl)
                .put("targetHosts", new JsonArray(targetHosts))
                .put("sites", sitesJson)
                .put("logUIHost", logUiHost)
                .put("auth", auth.toJson());
    }

    /**
     * @return the origin of a URL, e.g. {@code http://172.23.0.2:8023}. A URL without a protocol is taken to be http.
     */
    static String origin(String url){
        try{
            URL parsed = new URL(url.contains("://")? url : "http://" + url);
            return parsed.getProtocol() + "://" + parsed.getHost() + (parsed.getPort() != -1? ":" + parsed.getPort() : "");
        }catch (MalformedURLException e){
            throw new IllegalArgumentException("'%s' is not a valid URL: %s".formatted(url, e.getMessage()));
        }
    }

    /**
     * Like {@link #firstOf}, for a field OdoX can't start without.
     */
    private static String required(JsonObject environment, JsonObject experimentRequest, String key){
        String value = firstOf(environment, experimentRequest, key);
        if(value == null){
            throw new IllegalArgumentException("no '%s': set it in the task's 'environment', or on the experiment.".formatted(key));
        }
        return value;
    }

    private static String firstOf(JsonObject environment, JsonObject experimentRequest, String key){
        String value = string(environment, key);
        return value != null? value : string(experimentRequest, key);
    }

    private static String string(JsonObject json, String key){
        Object value = json.getValue(key);
        if(value == null){
            return null;
        }
        if(!(value instanceof String s)){
            throw new IllegalArgumentException("'%s' must be a string.".formatted(key));
        }
        return s.isBlank()? null : s;
    }

    private static List<String> hosts(JsonObject json){
        Object value = json.getValue("targetHosts");
        if(value == null){
            return null;
        }
        if(!(value instanceof JsonArray array)){
            throw new IllegalArgumentException("'targetHosts' must be an array of hosts, or [\"*\"] for every host.");
        }
        List<String> hosts = new ArrayList<>();
        array.forEach(host->hosts.add(String.valueOf(host)));
        return hosts;
    }

    private static Map<String, String> sites(JsonObject environment){
        Object value = environment.getValue("sites");
        if(value == null){
            return Map.of();
        }
        if(!(value instanceof JsonObject json)){
            throw new IllegalArgumentException("'environment.sites' must be an object of site names to base URLs.");
        }
        Map<String, String> sites = new LinkedHashMap<>();
        json.forEach(entry->sites.put(entry.getKey(), String.valueOf(entry.getValue())));
        return Collections.unmodifiableMap(sites);
    }
}
