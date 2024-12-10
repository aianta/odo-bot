package ca.ualberta.odobot.semanticflow;

import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.vertx.core.json.JsonObject;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.function.Predicate;

public class Utils {

    private static final Logger log = LoggerFactory.getLogger(Utils.class);

    /**
     * Utility method that converts output from {@link SqliteService#getSemanticSchemasWithSourceNodeIds()} into a Map.
     * @param input
     * @return
     */
    public static Map<SemanticSchema, String> schemaParametersToMap(List<JsonObject> input){
        Map<SemanticSchema, String> output = new HashMap<>();
        input.forEach(json->{
            SemanticSchema schema = new SemanticSchema(json);
            String sourceNodeId = json.getString("sourceNodeId");
            output.put(schema, sourceNodeId);
        });
        return output;
    }

    public static SemanticSchema getSchemaBySourceNodeId(Map<SemanticSchema, String> map, String sourceNodeId){
        Map.Entry<SemanticSchema, String> targetEntry = map.entrySet().stream().filter(entry->entry.getValue().equals(sourceNodeId)).findFirst().get();
        if(targetEntry != null){
            return targetEntry.getKey();
        }
        //log.warn("Did not find a schema associated with source node id: {}", sourceNodeId);
        return null;
    }

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
        return computeXpath(element, e->!e.tagName().equals("html") && !e.tagName().equals("body"));
    }

    /**
     * Like {@link #computeComponentXpath(Element)} but doesn't ignore html and body parts of xpath.
     * This is needed to hydrate component xpaths since we're hydrating from the JSOUP parsed component HTML document.
     * @param element
     * @return
     */
    public static String computeXpath(Element element){
//        return computeXpath(element, "", e->!e.tagName().equals("#root"));
        return computeXpath(element, e->true);
    }

    public static String computeXpathNoRoot(Element element){
        return computeXpath(element, e->!e.tagName().equals("#root"));
    }


    /**
     * Returns the xpath of a given element to its root
     * Logic ported over from: https://stackoverflow.com/questions/3454526/how-to-calculate-the-xpath-position-of-an-element-using-javascript
     *
     * @param element element for which to compute the xpath.
     * @param stopCondition custom stop condition
     * @return the xpath to the element.
     */
    public static String computeXpath(Element element, Predicate<Element> stopCondition){
        List<String> paths = new ArrayList<>();

        for(; element != null && element instanceof Element && stopCondition.test(element); element = element.parent()){

            int index = 0;
            boolean hasFollowingSiblings = false;
            for(Element sibling = element.previousElementSibling(); sibling != null; sibling = sibling.previousElementSibling()){

                if(sibling.nodeName() == element.nodeName()){
                    index++;
                }

            }

            for(Element sibling = element.nextElementSibling(); sibling != null && !hasFollowingSiblings; sibling = sibling.nextElementSibling()){
                if(sibling.nodeName() == element.nodeName()){
                    hasFollowingSiblings = true;
                }
            }

            String tagName = (element.tagName());
            String pathIndex = (index > 0 || hasFollowingSiblings)?("[" + (index + 1) + "]"):"";
            paths.add(0,tagName+pathIndex);

        }

        String result = "//";
        Iterator<String> it = paths.iterator();
        while (it.hasNext()){
            String segment = it.next();
            if(it.hasNext()){
                result += segment + "/";
            }else {
                result += segment;
            }
        }

        return result;

    }

}
