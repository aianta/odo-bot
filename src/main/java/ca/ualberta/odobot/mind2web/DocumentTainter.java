package ca.ualberta.odobot.mind2web;

import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.NodeIterator;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public class DocumentTainter {

    private static final Logger log = LoggerFactory.getLogger(DocumentTainter.class);

    public static final String TAINT = "odo-bot-taint";

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

        Elements elements = document.selectXpath(dxpath.getPrefix());

        if(elements.size() == 1){
            Element element = elements.get(0);
            taint(element); //Taint the prefix element.

            //Then taint the whole prefix leading to the root.
            Element cursor = element;
            while (cursor.parent() != null){
                cursor = cursor.parent();
                taint(element);
            }

            //Next taint the dynamic tags.
            //Do this by filtering through the prefixed element's children for all children whose tag matches the dynamic tag.
            List<Element> dynamicTags = element.children().stream()
                    .filter(e->e.tagName().equals(dxpath.getDynamicTag()))
                    .collect(Collectors.toList());

            //Taint all of these elements
            dynamicTags.forEach(DocumentTainter::taint);

            //Finally we need to taint all descendants of all the dynamic tags as well.
            dynamicTags.forEach(e->{
                NodeIterator<Element> descendants = new NodeIterator(e, Element.class);
                while (descendants.hasNext()){
                    Element descendant = descendants.next();
                    taint(descendant);
                }
            });

        }else{
            log.warn("[Taint Warning] Cannot taint, {} elements found for dynamic xpath prefix: {}", elements.size(), dxpath.getPrefix());
        }

        return document;

    }

    public static Document taint(Document document, List<String> xpaths){

        xpaths.forEach(xpath->taintXpath(document, xpath));

        return document;
    }

    private static Document taintXpath(Document document, String xpath){

        Elements elements = document.selectXpath(xpath);

        //Taint the element itself, then walk up it's parents all the way to the root node and taint everything along the way.
        if(elements.size() == 1){
            Element element = elements.get(0);
            taint(element);

            while (element.parent() != null){
                element = element.parent();
                taint(element);
            }
        }else{
            log.warn("[Taint Warning] Cannot taint, {} elements found for xpath: {}", elements.size(), xpath);
        }

        return document;
    }

    private static void taint(Element element){
        if(element.attributes().hasKey(TAINT)){
            return;
        }

        element.attributes().add(TAINT, null);
    }
}
