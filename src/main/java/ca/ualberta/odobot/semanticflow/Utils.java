package ca.ualberta.odobot.semanticflow;

import io.vertx.core.json.JsonObject;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.function.Predicate;

public class Utils {

    private static final Logger log = LoggerFactory.getLogger(Utils.class);

    public static String getNormalizedPath(String url){
        try{
            return getNormalizedPath(new URL(url));
        }catch (MalformedURLException e){
            log.error(e.getMessage(), e);
        }
        return null;
    }

    public static String getNormalizedPath(URL url){
        return url.getPath().replaceAll("[0-9]+", "*").replaceAll("(?<=pages\\/)[\\s\\S]+", "*");
    }

    public static JsonObject elementAttributesToJson(Element e){
        return e.attributes().asList().stream().collect(
                JsonObject::new,
                (json,attr)->json.put(attr.getKey(), attr.getValue()),
                (json1,json2)->json1.mergeIn(json2)
        );
    }

    /**
     * Computes an xpath from inside a component of a web page. NOT a full dom.
     * It explicitly ignores html and body elements.
     * @param element
     * @return
     */
    public static String computeComponentXpath(Element element){
        return computeXpath(element,"", e->!e.tagName().equals("html") && !e.tagName().equals("body"));
    }

    /**
     * Like {@link #computeComponentXpath(Element)} but doesn't ignore html and body parts of xpath.
     * This is needed to hydrate component xpaths since we're hydrating from the JSOUP parsed component HTML document.
     * @param element
     * @return
     */
    public static String computeXpath(Element element){
//        return computeXpath(element, "", e->!e.tagName().equals("#root"));
        return computeXpath(element, "", e->true);
    }

    /**
     * Returns the xpath of a given element to its root
     * @param element the element
     * @param xpath the recursive variable
     * @param stopCondition a predicate at which we stop computing the xpath.
     * @return the xpath to the element
     */
    public static String computeXpath(Element element, String xpath, Predicate<Element> stopCondition){

        String tag = element.tagName();
        int index = element.elementSiblingIndex(); //NOTE: siblingIndex() lies for some reason... It doesn't lie it gives me the node index

        String chunk = index > 0?(tag + "[" + Integer.toString(index) + "]"):tag;


        if(element.hasParent() && stopCondition.test(element.parent())){
            Element parent = element.parent();
            xpath = xpath.isEmpty()?chunk:(chunk + "/" + xpath);
            return computeXpath(parent, xpath, stopCondition);
        }else{
            if(xpath.isEmpty()){
                return "/" + chunk;
            }else{
                xpath = "/" + chunk + "/" + xpath;
                return xpath;
            }

        }
    }

}
