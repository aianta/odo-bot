package ca.ualberta.odobot.task.webarena;

/**
 * The helper functions of WebArena's {@code helper_functions.py} that query the shopping site's REST API rather than
 * the page. They block, so evaluators run on a worker thread.
 */
public interface WebArenaBackend {

    /**
     * {@code shopping_get_latest_order_url()}.
     */
    String shoppingGetLatestOrderUrl() throws Exception;

    /**
     * {@code shopping_get_sku_latest_review_author(sku)}.
     */
    String shoppingGetSkuLatestReviewAuthor(String sku) throws Exception;

    /**
     * {@code shopping_get_sku_latest_review_rating(sku)}.
     */
    String shoppingGetSkuLatestReviewRating(String sku) throws Exception;
}
