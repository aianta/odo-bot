package ca.ualberta.odobot.task.webarena;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * How a task's agent gets logged in to the sites it needs, from the {@code auth} object of the task's environment,
 * which the WebArena scheduler builds:
 * <pre>
 * "auth": {"mode": "prompt | session",
 *          "sites": [{"site": "gitlab", "baseUrl": "http://...", "loginUrl": "http://.../users/sign_in",
 *                     "username": "byteblaze", "password": "hello1234", "loggedInAtStart": true, "storageState": null}]}
 * </pre>
 *
 * <ul>
 *     <li>{@code prompt}: the agent logs in itself, from credentials in its task text. OdoBot does nothing.</li>
 *     <li>{@code session}: as in WebArena, the task starts logged in to every site with {@code loggedInAtStart}. OdoBot
 *     injects the site's Playwright-format {@code storageState} if it has one, or else logs in the way WebArena's
 *     {@code auto_login.py} does.</li>
 * </ul>
 *
 * @param mode {@code prompt} or {@code session}.
 */
public record TaskAuth(String mode, List<Site> sites) {

    public static final String PROMPT = "prompt";
    public static final String SESSION = "session";

    /**
     * @param storageState a Playwright storage state ({@code cookies}, {@code origins}) for the site, or null.
     */
    public record Site(String site, String baseUrl, String loginUrl, String username, String password,
                       boolean loggedInAtStart, JsonObject storageState){
    }

    public static TaskAuth prompt(){
        return new TaskAuth(PROMPT, List.of());
    }

    /**
     * @param auth the environment's {@code auth} object, or null for prompt mode.
     * @throws IllegalArgumentException if it is malformed.
     */
    public static TaskAuth fromJson(Object auth){
        if(auth == null){
            return prompt();
        }
        if(!(auth instanceof JsonObject json)){
            throw new IllegalArgumentException("'environment.auth' must be an object.");
        }
        String mode = json.getString("mode", PROMPT);
        if(!PROMPT.equals(mode) && !SESSION.equals(mode)){
            throw new IllegalArgumentException("'environment.auth.mode' must be 'prompt' or 'session', got '%s'.".formatted(mode));
        }
        List<Site> sites = new ArrayList<>();
        Object rawSites = json.getValue("sites", new JsonArray());
        if(!(rawSites instanceof JsonArray array)){
            throw new IllegalArgumentException("'environment.auth.sites' must be an array.");
        }
        for(Object o: array){
            if(!(o instanceof JsonObject s) || s.getString("site") == null){
                throw new IllegalArgumentException("Each of 'environment.auth.sites' needs a 'site'.");
            }
            Site site = new Site(s.getString("site"), s.getString("baseUrl"), s.getString("loginUrl"),
                    s.getString("username"), s.getString("password"), s.getBoolean("loggedInAtStart", false),
                    s.getJsonObject("storageState"));
            if(SESSION.equals(mode) && site.loggedInAtStart() && site.storageState() == null
                    && (site.baseUrl() == null || site.username() == null || site.password() == null)){
                throw new IllegalArgumentException("Site '%s' starts logged in, so it needs a storageState or a baseUrl, username and password."
                        .formatted(site.site()));
            }
            sites.add(site);
        }
        return new TaskAuth(mode, List.copyOf(sites));
    }

    /**
     * @return the sites to log in to before the agent starts: none in prompt mode.
     */
    public List<Site> sitesToLogIn(){
        return SESSION.equals(mode)? sites.stream().filter(Site::loggedInAtStart).toList() : List.of();
    }

    /**
     * @return the default accounts, with the credentials the auth block carries in their place, so the evaluators'
     * helpers use the same accounts as the login.
     */
    public WebArenaAccounts applyTo(WebArenaAccounts accounts){
        for(Site site: sites){
            if(site.username() != null && site.password() != null){
                accounts = accounts.with(site.site(), new WebArenaAccounts.Account(site.username(), site.password()));
            }
        }
        return accounts;
    }

    /**
     * @return the auth block without passwords or storage states, for the task's saved environment.
     */
    public JsonObject toJson(){
        JsonArray s = new JsonArray();
        for(Site site: sites){
            s.add(new JsonObject().put("site", site.site()).put("baseUrl", site.baseUrl()).put("loginUrl", site.loginUrl())
                    .put("username", site.username()).put("loggedInAtStart", site.loggedInAtStart())
                    .put("hasStorageState", site.storageState() != null));
        }
        return new JsonObject().put("mode", mode).put("sites", s);
    }
}
