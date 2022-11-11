package ca.ualberta.odobot.semanticflow;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class XPathProbabilityParser {

    private static final Logger log = LoggerFactory.getLogger(XPathProbabilityParser.class);
    private Multimap<String, String> watchedXpaths = ArrayListMultimap.create();
    private XPathProbabilities probabilities = new XPathProbabilities();

    public void parse(List<JsonObject> events){
        events.forEach(this::parse);

        log.info("XPathProbabilities: {}",probabilities.toString());
    }

    private void parse(JsonObject event) {
        try{
            switch (event.getString("eventType")){
                case "interactionEvent":
                    switch (event.getString("eventDetails_name")){
                        case "BUTTON_CLICK_ACTUAL":
                            handleClick(event);
                            break;
                        case "TD_CLICK":
                            handleClick(event);
                            break;
                        case "LINK_CLICK":
                            handleClick(event);
                            break;
                    }
                    break;
            }
        }catch (Exception e){
            log.error(e.getMessage(),e);
        }

    }

    private void handleClick(JsonObject event) throws Exception {

        JsonArray eventDetailsPath = new JsonArray(event.getString("eventDetails_path"));
        String targetElementXpath = event.getString("eventDetails_xpath");
        JsonObject targetElementDetails = eventDetailsPath.getJsonObject(0);
        String targetInnerHTML = targetElementDetails.getString("innerHTML");

        //Record an observation for the current button click.
        if (targetElementXpath.equals(targetElementDetails.getString("xpath"))){
            watchedXpaths.put(targetElementXpath, targetInnerHTML);
            probabilities.put(targetElementXpath, targetInnerHTML);
        }else{
            log.warn("Xpaths didn't match... {} and {}", targetElementXpath, targetElementDetails.getString("xpath"));
        }

        /*
         *  Iterate through our watched xpaths, and collect observations of their values.
         */
        probabilities.observeGivenThat(targetElementXpath, targetInnerHTML, introspect(eventDetailsPath, probabilities.watchedXpaths()));

    }

    private Map<String,String> introspect(JsonArray pathInfo, Iterable<String> targetXpaths) throws Exception{
        Optional<JsonObject> root = pathInfo.stream()
                .map(o->(JsonObject)o)
                .filter(json->json.getString("nodeName").equals("HTML")) //Find root in path
                .findFirst();

        if(!root.isPresent()){
            String msg = "Could not find root element in event path. Did bubbling get suppressed?";
            log.error(msg);
            throw new Exception(msg);
        }

        String rootHTML = root.get().getString("innerHTML");
        rootHTML = "<html>" + rootHTML + "</html>";
        Document document = Jsoup.parse(rootHTML);

        Map<String, String> observations = new HashMap<>();
        targetXpaths.forEach(xpath->{
            observations.put(xpath, introspect(document, xpath));
        });

        return observations;
    }

    private String introspect (Document document, String targetXpath){
        Elements elements = document.selectXpath(targetXpath);
        return elements.isEmpty()?"null":elements.html();
    }
}
