package ca.ualberta.odobot.mind2web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class HTMLCleaningTools {

    private static final Logger log = LoggerFactory.getLogger(HTMLCleaningTools.class);

    /**
     * Ported over logic from dom-effects.js which in turn was written following information from the stackoverflow below.
     * https://stackoverflow.com/questions/16585635/how-to-find-script-tag-from-the-string-with-javascript-regular-expression
     */
    private static final Pattern SCRIPT_TAG_REGEX = Pattern.compile("<script[\\s\\S]*?>[\\s\\S]*?<\\/script>", Pattern.CASE_INSENSITIVE );
    private static final Pattern XMLCDATA_TAG_REGEX = Pattern.compile("<!\\[CDATA[\\s\\S]*\\]\\]>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_TAG_REGEX = Pattern.compile("<style[\\s\\S]*?>[\\s\\S]*?<\\/style>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_PATHS_REGEX = Pattern.compile("<path[\\s\\S]*?>[\\s\\S]*?<\\/path>", Pattern.CASE_INSENSITIVE);

    /**
     * Define JSoup Safelist for cleaning.
     */
    private static Safelist safelist = Safelist.relaxed()
            .addAttributes(":all","backend_node_id")
            .addAttributes(":all", "class")
            .addAttributes(":all", "id")
            .addAttributes(":all", "name")
            .addAttributes(":all", "type")
            .addAttributes(":all", "disabled")
            .addAttributes(":all", "role")
            .addAttributes(":all", "alt")
            .addAttributes(":all", "input_value")
            .addAttributes(":all", "value")
            .addAttributes(":all", "placeholder")
            .addAttributes(":all", "aria_label")
            .addAttributes(":all", "title")
            .addAttributes(":all", "data_pw_testid_buckeye")
            .addAttributes(":all", "href")
            .addTags("input", "footer", "fieldset", "section", "button", "btn", "label", "article", "form", "text", "html", "body");


    public static String stripScriptTags(String input){
        return SCRIPT_TAG_REGEX.matcher(input).replaceAll("");
    }

    public static String stripXMLCDATATags(String input){
        return XMLCDATA_TAG_REGEX.matcher(input).replaceAll("");
    }

    public static String stripStyleTags(String input){
        return STYLE_TAG_REGEX.matcher(input).replaceAll("");
    }

    public static String stripSVGPaths(String input){
        return SVG_PATHS_REGEX.matcher(input).replaceAll("");
    }

    /**
     * Removes style, script, XMLCDATA, and SVG Path tags from the HTML input.
     *
     * Based off of cleaning technique from dom-effects.js.
     *
     * In practice it seems like using the JSoup cleaning method is better.
     *
     * @param input an HTML string to clean.
     * @return
     */
    @Deprecated
    public static String stripAll(String input){
        return Stream.of(input)
                .map(HTMLCleaningTools::stripScriptTags)
                .map(HTMLCleaningTools::stripStyleTags)
                .map(HTMLCleaningTools::stripXMLCDATATags)
                .map(HTMLCleaningTools::stripSVGPaths)
                .findFirst().get();
    }


    public static Set<String> getUnqiueAttributes(String html){
        Set<String> result = new HashSet<>();

        Document document = Jsoup.parse(html);
        for(Element e: document.getAllElements()){
            for (Attribute attr: e.attributes().asList()){
                result.add(attr.getKey());
            }
        }
        return result;
    }

    public static Set<String> getUnqiueTags(String html){
        Set<String> result = new HashSet<>();

        Document document = Jsoup.parse(html);
        for(Element e: document.getAllElements()){
            result.add(e.tagName());
        }
        return result;
    }

    public static String clean(String input){
        String result = null;

        result = input.replaceAll("\\\\n", ""); //get rid of any \n
        result = result.replaceAll("iframe", "div"); //swap iframes with divs
        result = result.replaceAll("\\\\\"", "\"");

        result = Jsoup.clean(result,"", safelist, new Document.OutputSettings().charset("UTF-8"));

        /**
         * The JSoup Clean mechanism produces/works on body content.
         * https://jsoup.org/apidocs/org/jsoup/safety/Cleaner.html
         *
         * So we have to rewrap everything in an <html></html>
         */
        result = "<html>%s</html>".formatted(result);

        return result;
    }
}
