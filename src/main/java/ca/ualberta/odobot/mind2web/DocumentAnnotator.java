package ca.ualberta.odobot.mind2web;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.impl.FutureConvertersImpl;

import java.util.List;

public class DocumentAnnotator {
    
    private static final Logger log = LoggerFactory.getLogger(DocumentAnnotator.class);
    
    public static Document annotate(Document document, List<String> xpaths, String annotation){

        int annotationCount = 0;
        for(String xpath:xpaths){
            if(annotate(document, xpath, annotation) != null){
                log.info("Annotated element at: {}", xpath);
                annotationCount++;
            };
        }

        log.info("Annotated {} elements with {}", annotationCount, annotation);

        return document;
    }

    /**
     *
     * @param document
     * @param xpath
     * @param annotation
     * @return the backend_node_id of the element that was annotated. -1 if no element was annotated.
     */
    public static String annotate(Document document, String xpath, String annotation){

        String absoluteXpath = Mind2WebUtils.toAbsoluteXpath(xpath);
        log.info("Original Xpath: {}", xpath);
        log.info("Absolute Xpath: {}", absoluteXpath);
        xpath = absoluteXpath;

        Elements targetElements = document.selectXpath(xpath);

        //If the xpath doesn't resolve to any elements there's nothing to annotate.
        if(targetElements.isEmpty()){
            return null;
        }

        Element targetElement = targetElements.get(0);


        if(targetElements.size() > 1){
            log.warn("{} elements matched annotation xpath, only annotating first matched element.", targetElements.size());
        }

        targetElement.attributes().add(annotation, null);

        //Taint descendants
        DocumentTainter.taintDescendants(targetElement);

        //Taint the element and all it's parents leading up to the root to ensure they do not get pruned.
        Element curr = targetElement;
        DocumentTainter.taint(curr);
        DocumentTainter.taintKeepId(curr);


        while (curr.parent() != null){
            curr = curr.parent();
            DocumentTainter.taint(curr);
        }



        return targetElement.attributes().get("backend_node_id");
    }
    
}
