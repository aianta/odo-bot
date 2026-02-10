package ca.ualberta.odobot.modelconstruction.impl;

import ca.ualberta.odobot.modelconstruction.CleaningStrategy;
import ca.ualberta.odobot.modelconstruction.impl.visitors.BlankRemovingVisitor;
import ca.ualberta.odobot.modelconstruction.impl.visitors.NodeLinksVisitor;
import ca.ualberta.odobot.mind2web.HTMLCleaningTools;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.NodeVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class TagAndAttributeStrategy implements CleaningStrategy {

    private static final Logger log = LoggerFactory.getLogger(TagAndAttributeStrategy.class);

    /**
     * Converts a DOM node to a node label to be used in a graph.
     * @param node
     * @return
     */
    public String nodeToLabel(Node node){
        if (node instanceof Element){
            Element element = (Element) node;
            StringBuilder sb = new StringBuilder();
            sb.append("<");

            sb.append(element.tagName());

            if(element.attributesSize() > 1){
                sb.append(" ");

                Iterator<Attribute> attrIt = element.attributes().asList().stream().filter(attribute -> !attribute.getKey().equals("vid")).toList().iterator();
                while (attrIt.hasNext()){
                    Attribute attr = attrIt.next();
                    sb.append(attr.getKey());
                    sb.append("='");
                    sb.append(attr.getValue());
                    sb.append("'");
                    if(attrIt.hasNext()){
                        sb.append(" ");
                    }
                }
            }
           sb.append(">");

            return sb.toString();
        }

        if (node instanceof TextNode){
            TextNode textNode = (TextNode) node;

            if (textNode.isBlank()){
                return "BLANK";
            }

            try{
                int i = Integer.parseInt(textNode.text());
                return "NUMERIC";

            }catch (NumberFormatException e){
                if(textNode.text().length() > 300){
                    return "LONG_TEXT";
                }else{
                    return "SHORT_TEXT";
                }
            }






        }


        return null;
    }


    //
    private class LabelingVisitor implements NodeVisitor {
        private static int COUNTER = 0;
        public Map<Integer, Node> nodeMap = new HashMap<Integer, Node>();
        public Map<Node, Integer> nodeIndex = new HashMap<>();
        @Override
        public void head(Node node, int i) {
            nodeMap.put(COUNTER, node);
            nodeIndex.put(node, COUNTER);
            COUNTER++;
        }

    }

    @Override
    public Future<String> cleanHTML(String input) {
        String html = HTMLCleaningTools.clean(input);

        Document doc = Jsoup.parse(html);
        doc.traverse(new BlankRemovingVisitor());
        doc.traverse(new LabelingVisitor());


        return Future.succeededFuture(doc.outerHtml());
    }

    public Future<JsonObject> toNodeLinks(String input){
        String html = HTMLCleaningTools.clean(input);

        Document doc = Jsoup.parse(html);
        doc.traverse(new BlankRemovingVisitor());
        LabelingVisitor labelingVisitor = new LabelingVisitor();
        doc.traverse(labelingVisitor);

        NodeLinksVisitor nodeLinksVisitor = new NodeLinksVisitor(this::nodeToLabel, labelingVisitor.nodeMap, labelingVisitor.nodeIndex );
        doc.traverse(nodeLinksVisitor);

        return Future.succeededFuture(nodeLinksVisitor.getGraphObject());
    }
}
