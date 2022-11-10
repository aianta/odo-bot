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

import java.util.List;

public class XPathProbabilityParser {

    private static final Logger log = LoggerFactory.getLogger(XPathProbabilityParser.class);
    private Multimap<String, String> watchedXpaths = ArrayListMultimap.create();

    public void parse(List<JsonObject> events){
        events.forEach(this::parse);

        log.info("watchedXPaths: {}",watchedXpaths);
    }

    private void parse(JsonObject event){

        switch (event.getString("eventType")){
            case "interactionEvent":
                switch (event.getString("eventDetails_name")){
                    case "BUTTON_CLICK_ACTUAL":
                        handleButtonClick(event);
                        break;
                }
                break;
        }
    }

    private void handleButtonClick(JsonObject event){

        JsonArray eventDetailsPath = new JsonArray(event.getString("eventDetails_path"));
        String targetElementXpath = event.getString("eventDetails_xpath");
        JsonObject targetElementDetails = eventDetailsPath.getJsonObject(0);

        if (targetElementXpath.equals(targetElementDetails.getString("xpath"))){
            watchedXpaths.put(targetElementXpath, targetElementDetails.getString("innerHTML"));
        }else{
            log.warn("Xpaths didn't match... {} and {}", targetElementXpath, targetElementDetails.getString("xpath"));
        }

        JsonObject rootElement = eventDetailsPath.getJsonObject(eventDetailsPath.size()-3);
        log.info("rootElement: NodeName:{} TagName:{}", rootElement.getString("nodeName"), rootElement.getString("tagName"));
        String rootHTML = rootElement.getString("innerHTML");
        String html = "<html>"+rootHTML+"</html>";
//        log.info("html\n{}", html);
        Document document = Jsoup.parse("<html>"+rootHTML+"</html>");
//        log.info("{}", document.outerHtml());
        Elements elements = document.selectXpath("/"+targetElementXpath);
        log.info("expecting: {}", targetElementDetails.getString("innerHTML"));
        log.info("if this works baby...{} -> {}",targetElementXpath, elements.toString());

    }
}
