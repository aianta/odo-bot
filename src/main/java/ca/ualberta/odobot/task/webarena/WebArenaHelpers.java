package ca.ualberta.odobot.task.webarena;

import ca.ualberta.odobot.task.webarena.py.PyRepr;
import ca.ualberta.odobot.task.webarena.py.PyUrl;

/**
 * WebArena's {@code helper_functions.py}: the functions its configs name in {@code func:} URLs and locators.
 */
public final class WebArenaHelpers {

    private WebArenaHelpers(){}

    /**
     * Run a {@code func:} call, as the original's {@code eval} would. Only WebArena's helpers can be called.
     *
     * @return the helper's result. Helpers return str, except {@code gitlab_get_project_memeber_role}, which returns
     * whatever the page gives.
     * @throws IllegalArgumentException for an unknown function, where Python raises NameError.
     */
    public static Object call(FuncCall call, WebArenaPage page, WebArenaBackend backend) throws Exception {
        return switch (call.name()){
            case "reddit_get_post_url" -> {
                call.expectArgs(1);
                yield redditGetPostUrl(call.stringArg(0));
            }
            case "shopping_get_latest_order_url" -> {
                call.expectArgs(0);
                yield backend.shoppingGetLatestOrderUrl();
            }
            case "shopping_get_sku_latest_review_author" -> {
                call.expectArgs(1);
                yield backend.shoppingGetSkuLatestReviewAuthor(call.stringArg(0));
            }
            case "shopping_get_sku_latest_review_rating" -> {
                call.expectArgs(1);
                yield backend.shoppingGetSkuLatestReviewRating(call.stringArg(0));
            }
            case "gitlab_get_project_memeber_role" -> {
                call.expectArgs(2);
                if(call.args().get(0) != FuncCall.PAGE){
                    throw new IllegalArgumentException("gitlab_get_project_memeber_role needs the page as its first argument");
                }
                yield gitlabGetProjectMemberRole(page, call.stringArg(1));
            }
            default -> throw new IllegalArgumentException("name '%s' is not defined".formatted(call.name()));
        };
    }

    /**
     * {@code reddit_get_post_url(url)}: the post URL of a post or comment URL, {@code scheme://domain/f/<sub>/<id>/},
     * or the URL as it is when it isn't one.
     */
    public static String redditGetPostUrl(String url){
        PyUrl.ParseResult parsed = PyUrl.urlparse(url);
        String[] tokUrl = parsed.path().split("/", -1);
        if(tokUrl.length < 4){
            return url;
        }
        if(!tokUrl[1].equals("f")){
            return url;
        }
        return "%s://%s/f/%s/%s/".formatted(parsed.scheme(), parsed.netloc(), tokUrl[2], tokUrl[3]);
    }

    /**
     * {@code gitlab_get_project_memeber_role(page, account_name)}: the role on the open members page of the account,
     * or "" if anything goes wrong, e.g. the account isn't listed.
     */
    public static Object gitlabGetProjectMemberRole(WebArenaPage page, String accountName){
        try{
            Object accountIdx = page.evaluate("""
                    (() => {
                                    const elements = document.querySelectorAll("td[data-label='Account'] span.gl-avatar-labeled-sublabel");
                                    let index = -1;  // Default value if not found

                                    for(let i = 0; i < elements.length; i++) {
                                        if(elements[i].outerText === '@{account_name}') {
                                            index = i;
                                            break;
                                        }
                                    }

                                    return index;
                                })()""".replace("{account_name}", accountName));
            //The original puts the index into the script with an f-string, i.e. str().
            return page.evaluate("""
                    (() => {
                                    return document.querySelectorAll("td.col-max-role span")[{account_idx}].outerText;
                                })()""".replace("{account_idx}", PyRepr.str(accountIdx)));
        }catch (Exception e){
            return "";
        }
    }
}
