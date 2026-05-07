package ca.ualberta.odobot.semanticflow;

import ca.ualberta.odobot.semanticflow.model.NetworkEvent;
import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import ca.ualberta.odobot.sqlite.SqliteService;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.util.CoreMap;
import io.vertx.core.json.JsonObject;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.function.Predicate;


import static ca.ualberta.odobot.logpreprocessor.LogPreprocessor.posPipeline;

public class Utils {

    private static final Logger log = LoggerFactory.getLogger(Utils.class);

    private static final Set<String> verbPOS = Set.of("VB","VBD","VBG","VBN","VBP","VBZ");

    public static Predicate<NetworkEvent> networkEventPredicate = (networkEvent) ->{
        if (networkEvent.getMethod().toLowerCase().equals("get")){
            return false;
        }

        Optional<String> graphQLOperation = networkEvent.getGraphQLOperationName();
        /**
         * Advanced filtering for GraphQL requests: if this is a GraphQL request, we want to include it only if it contains a mutation operation.
         * There is no standard way to determine this, but naming conventions often use "get" or "fetch" in the operation name for queries.
         * Some guides advise against that. So for generality, we will include any GraphQL request that HAS a verb in the operation name, which is NOT 'get' or 'fetch'.
         *
         * We assume camel/pascal case naming for GraphQL operations, and therefore split the operation name into words based on capital letters.
         * */
        if(graphQLOperation.isPresent()){
            String operationName = graphQLOperation.get();
            operationName = ca.ualberta.odobot.common.Utils.splitCamelCase(operationName).toLowerCase();
            operationName = operationName.replaceAll("update", "update the"); //Add "the" after "update" to make it more likely to be recognized as a verb by the POS tagger. Stupid context sensitive grammer rules...

            Annotation document = new Annotation(operationName);
            posPipeline.annotate(document);

            Set<String> verbs = new HashSet<>();

            List<CoreMap> sentences = document.get(CoreAnnotations.SentencesAnnotation.class);
            for (CoreMap sentence : sentences) {
                for (CoreLabel token: sentence.get(CoreAnnotations.TokensAnnotation.class)) {
                    String word = token.get(CoreAnnotations.TextAnnotation.class);
                    String pos = token.get(CoreAnnotations.PartOfSpeechAnnotation.class);
                    log.info("{} - {}", word, pos);
                    if(verbPOS.contains(pos)){
                        verbs.add(word.toLowerCase());
                    }
                }
            }

            return !verbs.isEmpty() && !verbs.contains("get") && !verbs.contains("fetch");


        }else {
            //If this is not a GraphQL request, it is a non-get network event, so include it.
            return true;
        }
    };

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
