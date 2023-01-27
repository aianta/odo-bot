package ca.ualberta.odobot.semanticflow;

import ca.ualberta.odobot.semanticflow.statemodel.Coordinate;
import ca.ualberta.odobot.semanticflow.statemodel.CoordinateData;
import ca.ualberta.odobot.semanticflow.statemodel.DOMSliverSequence;
import ca.ualberta.odobot.semanticflow.statemodel.Graph;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class StateParser {

    private static final Logger log = LoggerFactory.getLogger(StateParser.class);

    ModelManager manager = new ModelManager();

    public DOMSliverSequence parse(List<JsonObject> events){
        DOMSliverSequence sequence = new DOMSliverSequence();
        events.forEach(event->parse(event, sequence));
        return sequence;
    }

    private CoordinateData hydrateSliver(String xpath, Document document){

        CoordinateData result = new CoordinateData();
        Elements elements = document.selectXpath(xpath);
        if(elements.size() == 0){
            log.warn("Could not hydrate {}, xpath did not resolve!", xpath);
            return null;
        }
        Element element = document.selectXpath(xpath).get(0);
        result.setTag(element.tagName());

        result.setAttributes(element.attributes().asList().stream().collect(
                JsonObject::new,
                ( json, attr)->{
                    json.put(attr.getKey(), attr.getValue());
                },
                (json1, json2)->json1.mergeIn(json2)
        ));

        //result.setOuterHTML(element.outerHtml());

        return result;
    }

    /**
     * Captures tag, outerHTML*, event target status and attribute DOM information for a list of xpaths in a given document
     * @param xpaths the xpaths to extract info for
     * @param document the root DOM element in which to look up the xpaths.
     */
    private Map<String, CoordinateData> hydrateSliver(List<String> xpaths, Document document){
        /* Sort xpaths such that the longest path is at the end of the list. The coordinate data corresponding
         * to the last item in the list will be set as the event target.
         * */
        xpaths.sort(Comparator.comparingInt(String::length));
        Map<String, CoordinateData> result = new HashMap<>();

        ListIterator<String> it = xpaths.listIterator();
        while(it.hasNext()){
            int index = it.nextIndex();
            String xpath = it.next();

            CoordinateData data = hydrateSliver(xpath, document);

            //TODO - it shouldn't be the hydrate method's job to figure out event targets and prune outerHTML
//            if(index == xpaths.size() - 1){
//                data.setEventTarget(true);
//            }else{
//                //purge outerHTML info for non-event targets.
//                data.setOuterHTML(null);
//            }

            result.put(xpath, data);
        }

        return result;
    }

    private void parse(JsonObject event, DOMSliverSequence sequence) {

        String xpath = event.getString("eventDetails_xpath");
        log.info("xpath: {}", xpath);

        //TODO - ignore events without xpath, also ignore events that refer to unrooted elements. Like DOM_REMOVEs for example
        if(xpath == null || !xpath.contains("html")){
            return;
        }

        Coordinate c = ModelManager.materializeXpath(xpath);

        log.info("Materialized Coordinate: {}", c.toString());
        Set<Coordinate> coordinates = c.getRoot().toSet();
        List<String> xpaths = coordinates.stream().map(coordinate -> coordinate.xpath).collect(Collectors.toList());

        log.info("Set:{}", coordinates);
        log.info("xpaths: {}", xpaths);

        JsonObject domInfo = new JsonObject(event.getString("eventDetails_domSnapshot"));

        Map<String, CoordinateData> dataMap = hydrateSliver(xpaths, Jsoup.parse(domInfo.getString("outerHTML")));
        dataMap.forEach((xp,data)->{
            data.setEventId(event.getString("mongo_id"));
        });
        log.info("dataMap: {}",dataMap);

        /*
         * Bind the coordinates to the hydrated data
         */
        coordinates.forEach(coordinate -> coordinate.setData(dataMap.get(coordinate.xpath)));



        try{
            switch (event.getString("eventType")){
                case "interactionEvent":
                    sequence.merge(c);
                    break;
                case "customEvent":
                    if (event.getString("eventDetails_name").equals("DOM_EFFECT")){
                        switch (event.getString("eventDetails_action")){
                            case "add":
                                sequence.merge(handleDOMAdd(event));
                                break;
                            case "show":
                                sequence.merge(c);
                                break;
                            case "hide":
                                //TODO
                                break;
                            case "remove":
                                //TODO
                                break;
                        }
                    }

            }
        }catch (Exception e){
            log.error(e.getMessage(),e);
        }

        log.info("last fully processed eventId: {}", event.getString("mongo_id"));
    }


    private Map<String, CoordinateData> hydrateComponent(String attachPoint, Document componentDOM){
        Elements elements = componentDOM.select("*:not(:has(*))");

        Set<String> exlcude = Set.of("html","head", "body", "#root");


        Map<String,CoordinateData> dataMap = hydrateSliver(
                elements.stream()
//                        .filter(element -> !exlcude.contains(element.tagName()))
                        .peek(element -> log.info("tag: {}", element.tagName()))
                        .map(element -> computeXpath(element))
                        .peek(computed->log.info("computedXpathForHydration: {}", computed))
                        .collect(Collectors.toList()),
                componentDOM
        );

        /**
         * So JSoup created a <html><head></head><body></body><html> wrapper around HTML that it parses.
         * Thus the xpaths we need to retrieve the information for component data above, are not
         * the ones we actually want to keep around for our model. And the code below, strips away
         * these JSOUP added elements from the xpaths, and computes the actual xpaths of elements
         * in the component which can be used to bind the data to the coordinates.
         */

        final String prefix = "/html/body[1]";
        for(String dataKey: dataMap.keySet()){
            String realKey = dataKey.substring(prefix.length()-1);
            realKey = fuseXpaths(attachPoint, realKey);
            dataMap.put(realKey, dataMap.get(dataKey));
            dataMap.remove(dataKey);
        }

        return dataMap;
    }

    /**
     * A DOM add is handled by creating a set of coordinates describing the leaves of the added HTML element and
     * their relationship with the attach point (the place where they were inserted into the DOM).
     *
     * @param event the dom add event to process.
     * @return A coordinate structure rooted at the attatch point and extending to the leaves of the added HTML element.
     */
    private Coordinate handleDOMAdd(JsonObject event){
        //Sanity check our event
        if (!event.getString("eventDetails_action").equals("add")){
            log.warn("handleDOMAdd called on an event whose 'eventDetails_action' field was not 'add'! Instead got {}",
                    event.getString("eventDetails_action"));
        }

        //The data for the node that was added is stored as the first object in the 'eventDetails_nodes' array. Let's extract that.
        JsonArray addedNodes = new JsonArray(event.getString("eventDetails_nodes"));
        JsonObject addedNode = addedNodes.getJsonObject(0);

        //Let's parse the dom of this object with JSoup by reading its outerHTML
        String outerHTML = addedNode.getString("outerHTML");
        Document addedDOM = Jsoup.parse(outerHTML);
        Elements elements = addedDOM.select("*:not(:has(*))"); //Select leaf elements

        log.info("outerHTML:\n{}", outerHTML);
        log.info("jsoupHTML:\n{}", addedDOM.outerHtml());

        String attachPointXpath = addedNode.getString("xpath");
        Coordinate attachPoint = ModelManager.materializeXpath(attachPointXpath);

        /**
         * TODO Fetch coordinate data from
         */
        // Map<String, CoordinateData> dataMap = hydrateComponent(attachPointXpath, addedDOM);

        //For each leaf element materialize a structure leading up to the attach point and add its root as a child to the attach point
        List<Coordinate> leaves = elements.stream()
                /** Filter out 'head' elements because they are not actually part of the added component.
                 * JSoup created a <html><head></head><body></body><html> wrapper around HTML that it parses
                 * which lacks them. Thus we should ignore the empty 'head' leaf. The html and body tags are handled
                 * in {@link #computeComponentXpath(Element)}.
                 */
                .filter(element->!element.tagName().equals("head"))
                /**
                 * Compute internal component xpaths for the remaining HTML elements and fuse the
                 * component xpaths to the xpath of the attach point. Thus creating the xpath from the
                 * root of the document to the attached leaf.
                 */
                .map(element -> fuseXpaths(attachPointXpath, computeComponentXpath(element)))
                .peek(s->log.info("leaf xpath: {}", s))
                /**
                 * Materialize the leaf xpaths into coordinates. Use the attach point as the stop condition.
                 * That is, we will only materialize the 'internal' part of the xpath.
                 */
                .map(xpath->ModelManager.materializeXpath(attachPointXpath, xpath))
                .peek(coordinate -> log.info("leaf coordinate: {}", coordinate))
                .collect(Collectors.toList());

                if(leaves.size() == 0){
                    //Handle situationn with no leaves. I don't think this should be possible but...
                    log.warn("No leaves in DOM_ADD");
                    return attachPoint;
                }

                ListIterator<Coordinate> it = leaves.listIterator();
                Graph cursorGraph = it.next().toGraph();
                while (it.hasNext()){
                    Graph nextGraph = it.next().toGraph();
                    cursorGraph = Graph.merge(cursorGraph, nextGraph);
                }

                Set<Coordinate> componentLeaves = cursorGraph.toCoordinate();
                componentLeaves.forEach(leaf->{
                    attachPoint.addChild(leaf);
                });

                log.info("Attach point has {} children", attachPoint.numChildren());

        //TODO Bind the hydrated data
        //attachPoint.getRoot().toSet().forEach(coordinate -> coordinate.setData(dataMap.get(coordinate.xpath)));

        return attachPoint;
    }


    /**
     * Fuses the external xpath to an attach point, and an internal xpath from within the attached structure together
     * to form an absolute xpath.
     * @param external the xpath to the attach point
     * @param internal the xpath to an internal element in the attached structure
     * @return the absolute path of the internal attached structure.
     */
    public static String fuseXpaths(String external, String internal){
        //Strip the first slash off the internal xpath
        internal = internal.substring(1);

        //If there are slashes left in internal
        if(internal.contains("/")){
            //strip everything before the next slash
            internal = internal.substring(internal.indexOf('/'));
            return external + internal;
        }else{
            //This should only happen if the attach point is a leaf
            return external;
        }
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
     * @return the xpath to the element
     */
    public static String computeXpath(Element element, String xpath, Predicate<Element> stopCondition){

        String tag = element.tagName();
        int index = element.elementSiblingIndex(); //NOTE: siblingIndex() lies for some reason...

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
