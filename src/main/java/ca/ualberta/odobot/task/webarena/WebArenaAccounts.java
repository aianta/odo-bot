package ca.ualberta.odobot.task.webarena;

import io.vertx.core.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebArena's {@code ACCOUNTS} ({@code browser_env/env_config.py}), which the scripted logins and the shopping REST
 * helpers use. The defaults are those of the stock WebArena images; an evaluate request can override any of them under
 * {@code webarenaEvaluation.accounts}, e.g. {@code {"gitlab": {"username": "...", "password": "..."}}}.
 */
public final class WebArenaAccounts {

    public record Account(String username, String password){}

    public static final String REDDIT = "reddit";
    public static final String GITLAB = "gitlab";
    public static final String SHOPPING = "shopping";
    public static final String SHOPPING_ADMIN = "shopping_admin";
    public static final String SHOPPING_SITE_ADMIN = "shopping_site_admin";

    private static final Map<String, Account> DEFAULTS = Map.of(
            REDDIT, new Account("MarvelsGrantMan136", "test1234"),
            GITLAB, new Account("byteblaze", "hello1234"),
            SHOPPING, new Account("emma.lopez@gmail.com", "Password.123"),
            SHOPPING_ADMIN, new Account("admin", "admin1234"),
            SHOPPING_SITE_ADMIN, new Account("admin", "admin1234")
    );

    private final Map<String, Account> accounts;

    private WebArenaAccounts(Map<String, Account> accounts){
        this.accounts = accounts;
    }

    public static WebArenaAccounts defaults(){
        return new WebArenaAccounts(new LinkedHashMap<>(DEFAULTS));
    }

    /**
     * @param overrides {@code {site: {username, password}}}, or null for the defaults.
     * @throws IllegalArgumentException if an override has no username or password.
     */
    public static WebArenaAccounts fromJson(JsonObject overrides){
        Map<String, Account> accounts = new LinkedHashMap<>(DEFAULTS);
        if(overrides != null){
            overrides.forEach(entry->{
                if(!(entry.getValue() instanceof JsonObject account)
                        || account.getString("username") == null || account.getString("password") == null){
                    throw new IllegalArgumentException("'webarenaEvaluation.accounts.%s' needs a username and a password.".formatted(entry.getKey()));
                }
                accounts.put(entry.getKey(), new Account(account.getString("username"), account.getString("password")));
            });
        }
        return new WebArenaAccounts(accounts);
    }

    /**
     * @return a copy with one account replaced, e.g. by the credentials the task's auth block carries.
     */
    public WebArenaAccounts with(String site, Account account){
        Map<String, Account> copy = new LinkedHashMap<>(accounts);
        copy.put(site, account);
        return new WebArenaAccounts(copy);
    }

    /**
     * @throws IllegalArgumentException for a site without an account.
     */
    public Account get(String site){
        Account account = accounts.get(site);
        if(account == null){
            throw new IllegalArgumentException("No WebArena account for site '%s'.".formatted(site));
        }
        return account;
    }
}
