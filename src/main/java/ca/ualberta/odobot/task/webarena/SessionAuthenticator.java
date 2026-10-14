package ca.ualberta.odobot.task.webarena;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Date;
import java.util.List;

import static ca.ualberta.odobot.task.webarena.PlaywrightLocators.*;

/**
 * Logs a task's browser in to its sites before the agent starts, as WebArena does for every task: {@code run.py}
 * renews the session of the sites in the task's {@code storage_state} with {@code auto_login.py}, and opens the start
 * page in a context built from it.
 *
 * <p>Each site is logged in with the steps of {@code auto_login.renew_comb}, in its order (shopping, reddit,
 * shopping_admin, gitlab), unless the auth block carries a storage state for it, which is injected instead. Each login
 * is then checked with {@code auto_login.is_expired}'s checks. Everything happens in a scratch tab that is closed
 * afterwards, so the agent's tab has no login pages in its history. It runs before OdoX connects, so none of it reaches
 * the task's event log.</p>
 *
 * <p>Blocking: run it on a worker thread.</p>
 */
public final class SessionAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(SessionAuthenticator.class);

    private static final List<String> ORDER = List.of("shopping", "reddit", "shopping_admin", "gitlab");

    private static final int ATTEMPTS = 2;

    /**
     * A site could not be logged in to.
     */
    public static class AuthFailedException extends RuntimeException {
        public final JsonObject report;

        AuthFailedException(String site, JsonObject report){
            super("auth: " + site);
            this.report = report;
        }
    }

    private SessionAuthenticator(){}

    /**
     * Log in to the sites the task starts logged in to.
     *
     * @return {@code {mode, sites: {site: {status, method, ms, error?}}}}.
     * @throws AuthFailedException if a site could not be logged in to after {@value #ATTEMPTS} attempts.
     */
    public static JsonObject authenticate(WebDriver driver, TaskAuth auth){
        JsonObject sites = new JsonObject();
        JsonObject report = new JsonObject().put("mode", auth.mode()).put("sites", sites);
        List<TaskAuth.Site> toLogIn = auth.sitesToLogIn().stream()
                .sorted((a, b)->Integer.compare(order(a.site()), order(b.site())))
                .toList();
        if(toLogIn.isEmpty()){
            return report;
        }

        String appTab = driver.getWindowHandle();
        driver.switchTo().newWindow(WindowType.TAB);
        String scratchTab = driver.getWindowHandle();
        try{
            for(TaskAuth.Site site: toLogIn){
                JsonObject siteReport = new JsonObject().put("method", site.storageState() != null? "storageState" : "login");
                sites.put(site.site(), siteReport);
                long started = System.nanoTime();
                String error = null;
                int attempts = 0;
                for(int attempt = 1; attempt <= ATTEMPTS; attempt++){
                    attempts = attempt;
                    try{
                        if(site.storageState() != null){
                            injectStorageState(driver, site);
                        }else{
                            login(driver, site);
                        }
                        String expired = expiredReason(driver, site);
                        if(expired == null){
                            error = null;
                            break;
                        }
                        error = expired;
                    }catch (RuntimeException e){
                        error = e.toString();
                    }
                    log.warn("Login to {} failed (attempt {} of {}): {}", site.site(), attempt, ATTEMPTS, error);
                }
                siteReport.put("attempts", attempts).put("ms", (System.nanoTime() - started) / 1_000_000);
                if(error != null){
                    siteReport.put("status", "failed").put("error", error);
                    throw new AuthFailedException(site.site(), report);
                }
                siteReport.put("status", "ok");
            }
        }finally {
            try{
                driver.switchTo().window(scratchTab);
                driver.close();
            }finally {
                driver.switchTo().window(appTab);
            }
        }
        return report;
    }

    private static int order(String site){
        int i = ORDER.indexOf(site);
        return i < 0? ORDER.size() : i;
    }

    private static String base(TaskAuth.Site site){
        return site.baseUrl().replaceAll("/+$", "");
    }

    /**
     * The steps of {@code auto_login.renew_comb} for one site.
     */
    static void login(WebDriver driver, TaskAuth.Site site){
        String start;
        switch (site.site()){
            case "shopping" -> {
                start = site.loginUrl() != null? site.loginUrl() : base(site) + "/customer/account/login/";
                driver.get(start);
                fill(byLabel(driver, "Email", true), site.username());
                fill(byLabel(driver, "Password", true), site.password());
                buttonByName(driver, "Sign In").click();
            }
            case "reddit" -> {
                start = site.loginUrl() != null? site.loginUrl() : base(site) + "/login";
                driver.get(start);
                fill(byLabel(driver, "Username", false), site.username());
                fill(byLabel(driver, "Password", false), site.password());
                buttonByName(driver, "Log in").click();
            }
            case "shopping_admin" -> {
                start = site.loginUrl() != null? site.loginUrl() : base(site) + "/admin";
                driver.get(start);
                fill(byPlaceholder(driver, "user name"), site.username());
                fill(byPlaceholder(driver, "password"), site.password());
                buttonByName(driver, "Sign in").click();
            }
            case "gitlab" -> {
                start = site.loginUrl() != null? site.loginUrl() : base(site) + "/users/sign_in";
                driver.get(start);
                byTestId(driver, "username-field").click();
                fill(byTestId(driver, "username-field"), site.username());
                byTestId(driver, "username-field").sendKeys(Keys.TAB);
                fill(byTestId(driver, "password-field"), site.password());
                byTestId(driver, "sign-in-button").click();
            }
            default -> throw new IllegalArgumentException("WebArena has no login for site '%s'".formatted(site.site()));
        }
        //Playwright's click waits for the navigation it starts; wait for the page to leave the login form.
        try{
            new WebDriverWait(driver, Duration.ofSeconds(30)).until(d->!start.equals(d.getCurrentUrl())
                    && "complete".equals(((JavascriptExecutor) d).executeScript("return document.readyState")));
        }catch (RuntimeException e){
            //Some sites stay on the same URL; the expiry check decides.
        }
    }

    /**
     * {@code auto_login.is_expired}'s check for the site: its account page must open, and contain its keyword.
     *
     * @return why the session is not logged in, or null if it is.
     */
    static String expiredReason(WebDriver driver, TaskAuth.Site site){
        String base = base(site);
        String url;
        String keyword;
        boolean urlExact;
        switch (site.site()){
            case "gitlab" -> { url = base + "/-/profile"; keyword = ""; urlExact = true; }
            case "shopping" -> { url = base + "/wishlist/"; keyword = ""; urlExact = true; }
            //WebArena's SHOPPING_ADMIN includes /admin.
            case "shopping_admin" -> { url = (base.endsWith("/admin")? base : base + "/admin") + "/dashboard"; keyword = "Dashboard"; urlExact = true; }
            case "reddit" -> { url = base + "/user/" + site.username() + "/account"; keyword = "Delete"; urlExact = true; }
            default -> { return "no check for site " + site.site(); }
        }
        driver.get(url);
        try{
            Thread.sleep(1000);
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }
        if(!keyword.isEmpty()){
            String content = String.valueOf(((JavascriptExecutor) driver).executeScript(
                    "return (document.doctype ? new XMLSerializer().serializeToString(document.doctype) : '') + document.documentElement.outerHTML;"));
            return content.contains(keyword)? null : "'%s' is not on %s".formatted(keyword, url);
        }
        String landed = driver.getCurrentUrl();
        boolean expired = urlExact? !landed.equals(url) : !landed.contains(url);
        return expired? "%s redirected to %s".formatted(url, landed) : null;
    }

    /**
     * Apply a Playwright storage state: its cookies, and its origins' localStorage.
     */
    static void injectStorageState(WebDriver driver, TaskAuth.Site site){
        JsonObject state = site.storageState();
        driver.get(base(site) + "/");
        for(Object o: state.getJsonArray("cookies", new JsonArray())){
            JsonObject c = (JsonObject) o;
            Cookie.Builder cookie = new Cookie.Builder(c.getString("name"), c.getString("value"))
                    .path(c.getString("path", "/"))
                    .isHttpOnly(c.getBoolean("httpOnly", false))
                    .isSecure(c.getBoolean("secure", false));
            String domain = c.getString("domain");
            if(domain != null){
                cookie.domain(domain);
            }
            Double expires = c.getDouble("expires");
            if(expires != null && expires > 0){
                cookie.expiresOn(new Date((long) (expires * 1000)));
            }
            String sameSite = c.getString("sameSite");
            if(sameSite != null){
                cookie.sameSite(sameSite);
            }
            driver.manage().addCookie(cookie.build());
        }
        for(Object o: state.getJsonArray("origins", new JsonArray())){
            JsonObject origin = (JsonObject) o;
            driver.get(origin.getString("origin") + "/");
            for(Object item: origin.getJsonArray("localStorage", new JsonArray())){
                JsonObject entry = (JsonObject) item;
                ((JavascriptExecutor) driver).executeScript("localStorage.setItem(arguments[0], arguments[1]);",
                        entry.getString("name"), entry.getString("value"));
            }
        }
    }
}
