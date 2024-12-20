package ca.ualberta.odobot.mind2web;

import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import io.vertx.core.json.JsonArray;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.NodeIterator;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public class DocumentTainter {

    private static final Logger log = LoggerFactory.getLogger(DocumentTainter.class);

    public static final String TAINT = "odo-bot-taint";

    public static final String KEEP_ID_TAINT = "odo-bot-keep-id";


    public static Document taintWithDynamicXpaths(Document document, List<DynamicXPath> dxpaths){

        dxpaths.forEach(dxpath->taintDynamicXpath(document, dxpath));

        return document;
    }

    private static Document taintDynamicXpath(Document document, DynamicXPath dxpath){

        /**
         * Steps:
         *  1) taint the prefix
         *  2) taint the dynamic tags
         *  3) taint descendants
          */

        Elements prefixElements = document.selectXpath(dxpath.getPrefix());

        /**
         * There are situations in which the xpath corresponding to the dxpath prefix will return multiple elements.
         * See notes under thoughts 2024 -> 'October 30th - State Abstraction v1 Showtime' -> 'One Xpath multiple results investigation'
         */
        if (prefixElements.size() >= 1) {

            for(Element e: prefixElements){
                taint(e); //Taint prefix element

                //Then taint the whole prefix leading to the root.
                Element cursor = e;
                while (cursor.parent() != null){
                    cursor = cursor.parent();
                    taint(cursor);
                }

                //Next taint the dynamic tags.
                //Do this by filtering through the prefixed element's children for all children whose tag matches the dynamic tag.
                List<Element> dynamicTags = e.children().stream()
                        .filter(el->el.tagName().equals(dxpath.getDynamicTag()))
                        .collect(Collectors.toList());

                //Taint all of these elements
                dynamicTags.forEach(DocumentTainter::taint);
                dynamicTags.forEach(DocumentTainter::taintKeepId);

                //Finally we need to taint all descendants of all the dynamic tags as well.
                dynamicTags.forEach(DocumentTainter::taintDescendants);

            }



        } else{
            //log.warn("[Taint Warning] Cannot taint, {} elements found for dynamic xpath prefix: {}", prefixElements.size(), dxpath.getPrefix());
        }

        return document;

    }

    public static Document taint(Document document, JsonArray backendNodeIds){


        backendNodeIds.stream().map(o->(String)o).forEach(id->taintBackendNodeId(document, id));

        return document;
    }

    public static Document taintBackendNodeId(Document document, String backendNodeId){
        Elements candidates = document.selectXpath("//*[@backend_node_id='%s']".formatted(backendNodeId));

        assert candidates.size() == 0 || candidates.size() == 1;

        if(candidates.size() == 1){
            //Taint the element itself,
            Element element = candidates.get(0);
            taint(element);
            taintKeepId(element); //Keep the id of the candidate element

            //Taint descendants of the target element
            taintDescendants(element);

            //then walk up it's parents all the way to the root node and taint everything along the way.
            while (element.parent() != null){
                element = element.parent();
                taint(element);
            }
        }

        return document;
    }

    public static Document taint(Document document, List<String> xpaths){

        xpaths.forEach(xpath->taintXpath(document, xpath));

        return document;
    }

    private static Document taintXpath(Document document, String xpath){

        Elements elements = document.selectXpath(xpath);

        if(elements.size() >= 1){
            //Taint the element itself,
            Element element = elements.get(0);
            taint(element);
            taintKeepId(element); //Keep the id of the target element

            //Taint descendants of the target element
            taintDescendants(element);

            //then walk up it's parents all the way to the root node and taint everything along the way.
            while (element.parent() != null){
                element = element.parent();
                taint(element);
            }
        }else if (elements.size() > 1){
            log.warn("Xpath matched {} elements! Did not taint.", elements.size());
        }

        return document;
    }

    public static void taint(Node element){
        if(element.attributes().hasKey(TAINT)){
            return;
        }

        element.attributes().add(TAINT, null);
    }

    public static void taintKeepId(Node element){
        if(element.attributes().hasKey(KEEP_ID_TAINT)){
            return;
        }
        element.attributes().add(KEEP_ID_TAINT, null);
    }

    public static void taintDescendants(Node element){
        taintDescendants(element, 5);
    }
    public static void taintDescendants(Node element, int max){
        NodeIterator<Element> descendants = new NodeIterator<>(element, Element.class);
        int count = 0;
        while (descendants.hasNext() && count < max){
            Element descendant = descendants.next();
            taint(descendant);
            count++;
        }
    }
}
