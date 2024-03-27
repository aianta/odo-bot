package ca.ualberta.odobot.domsequencing;

import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Alexandru Ianta
 *
 * Java implementation of getElementTree XPath from firebug (now firefox developer edition)
 * https://github.com/firebug/firebug/blob/master/extension/content/firebug/lib/xpath.js
 *
 */
public class XPath {
    private static final Logger log = LoggerFactory.getLogger(XPath.class);
    public static String getXPath(Element element){

        List<String> paths = new ArrayList<>();

        for(;element != null; element=element.parent()){
            if(element.tagName().equals("#root")){
                continue; //Skip the jsoup root tag.
            }

            int index = 0;
            boolean hasFollowingSiblings = false;
            for(Element sibling = element.previousElementSibling(); sibling != null; sibling = sibling.previousElementSibling()){

                /* Firebug source ignores document type declaration here, but I don't think that is
                   for us because we're dealing with JSoup elements. If we get weird output though, this
                   is one place to check.
                 */


                if(sibling.nodeName() == element.nodeName()){
                    ++index;
                }
            }

            for (Element sibling = element.nextElementSibling(); sibling != null && !hasFollowingSiblings; sibling = sibling.nextElementSibling()){
                if(sibling.nodeName() == element.nodeName()){
                    hasFollowingSiblings = true;
                }
            }

            /* Firebug implementation keeps track of element prefix as well. But element prefixes should only appear in XML documents.
               So we should be able to safely discount this since we're working exclusively with HTML here.
             */
            String tagName = element.tagName(); //element.normalName() also seems like a good candidate here.
            String pathIndex = (index != 0 || hasFollowingSiblings)? ("[" + (index + 1) + "]") : "";
            paths.add(0, tagName + pathIndex);

        }

        return paths.size() > 0? "/" + String.join("/", paths): null;

    }

}
