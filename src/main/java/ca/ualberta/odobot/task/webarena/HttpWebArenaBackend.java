package ca.ualberta.odobot.task.webarena;

import ca.ualberta.odobot.task.webarena.py.PyRepr;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The shopping REST helpers of WebArena's {@code helper_functions.py}, against the task's own shopping site. Like the
 * original, every call fetches a fresh admin token, and a response other than 200 is an error (the original asserts).
 * Each request made is recorded, for the task's evaluation trace.
 */
public class HttpWebArenaBackend implements WebArenaBackend {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String shopping;
    private final WebArenaAccounts.Account siteAdmin;
    private final HttpClient http;
    private final List<JsonObject> calls = new ArrayList<>();

    /**
     * @param shopping the shopping site's base URL (WebArena's {@code SHOPPING}), or null if the task's environment
     *                 has none, in which case every helper fails.
     * @param siteAdmin WebArena's {@code shopping_site_admin} account.
     */
    public HttpWebArenaBackend(String shopping, WebArenaAccounts.Account siteAdmin){
        this.shopping = shopping;
        this.siteAdmin = siteAdmin;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /**
     * @return the requests made so far: method, URL, status.
     */
    public synchronized List<JsonObject> calls(){
        return List.copyOf(calls);
    }

    private String base(){
        if(shopping == null){
            throw new IllegalStateException("The task's environment has no 'shopping' site, which the shopping helpers query.");
        }
        return shopping;
    }

    /**
     * {@code shopping_get_auth_token()}. The original does not check the status; whatever the body decodes to is used.
     */
    private String authToken() throws IOException, InterruptedException {
        String body = new JsonObject().put("username", siteAdmin.username()).put("password", siteAdmin.password()).encode();
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base() + "/rest/default/V1/integration/admin/token"))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        return PyRepr.str(PyRepr.fromJson(Json.decodeValue(response.body())));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpRequest built = request.timeout(TIMEOUT).build();
        HttpResponse<String> response = http.send(built, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        synchronized (this){
            calls.add(new JsonObject()
                    .put("method", built.method())
                    .put("url", built.uri().toString())
                    .put("status", response.statusCode()));
        }
        return response;
    }

    private Object getJson(String url, String token) throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .GET());
        if(response.statusCode() != 200){
            throw new IllegalStateException("GET %s returned %d (the original asserts 200)".formatted(url, response.statusCode()));
        }
        return Json.decodeValue(response.body());
    }

    @Override
    public String shoppingGetLatestOrderUrl() throws IOException, InterruptedException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("searchCriteria[sortOrders][0][field]", "created_at");
        params.put("searchCriteria[sortOrders][0][direction]", "DESC");
        params.put("searchCriteria[pageSize]", "1");
        String query = params.entrySet().stream()
                .map(e->URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        JsonObject orders = (JsonObject) getJson(base() + "/rest/V1/orders?" + query, authToken());
        JsonObject latest = orders.getJsonArray("items").getJsonObject(0);
        //Python's int(increment_id) drops the leading zeros.
        long orderId = Long.parseLong(PyRepr.str(PyRepr.fromJson(latest.getValue("increment_id"))).strip());
        return "%s/sales/order/view/order_id/%d/".formatted(base(), orderId);
    }

    @Override
    public String shoppingGetSkuLatestReviewAuthor(String sku) throws IOException, InterruptedException {
        JsonArray reviews = (JsonArray) getJson(base() + "/rest/V1/products/" + sku + "/reviews", authToken());
        if(reviews.isEmpty()){
            return "";
        }
        return (String) PyRepr.fromJson(reviews.getJsonObject(reviews.size() - 1).getValue("nickname"));
    }

    @Override
    public String shoppingGetSkuLatestReviewRating(String sku) throws IOException, InterruptedException {
        JsonArray reviews = (JsonArray) getJson(base() + "/rest/V1/products/" + sku + "/reviews", authToken());
        if(reviews.isEmpty()){
            return "";
        }
        String ratingName = reviews.getJsonObject(0).getJsonArray("ratings").getJsonObject(0).getString("rating_name");
        if(!"Rating".equals(ratingName)){
            throw new IllegalStateException("The first review's first rating is '%s', not 'Rating' (the original asserts it)".formatted(ratingName));
        }
        Object percent = reviews.getJsonObject(reviews.size() - 1).getJsonArray("ratings").getJsonObject(0).getValue("percent");
        return PyRepr.str(PyRepr.fromJson(percent));
    }
}
