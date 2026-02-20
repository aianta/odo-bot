package ca.ualberta.odobot.modelconstruction.impl;

import ca.ualberta.odobot.common.Utils;
import ca.ualberta.odobot.common.Xpath;
import ca.ualberta.odobot.modelconstruction.CleaningStrategy;
import ca.ualberta.odobot.modelconstruction.impl.visitors.BlankRemovingVisitor;
import ca.ualberta.odobot.modelconstruction.impl.visitors.NodeLinksVisitor;
import ca.ualberta.odobot.mind2web.HTMLCleaningTools;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.NodeVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

import static ca.ualberta.odobot.semanticflow.Utils.computeXpathNoRoot;

public class TagAndAttributeStrategy implements CleaningStrategy {

    private static final Logger log = LoggerFactory.getLogger(TagAndAttributeStrategy.class);

    private Vertx vertx;
    public TagAndAttributeStrategy(Vertx vertx) {
        this.vertx = vertx;
        attributesWhoseValuesMustBeProcessed.put("href", (value)-> Utils.normalizeBaseUriV2(value));
    }

    //Certain attribute values are very likely to be content specific
    //Useful for attribute who's presence is still meaningful.
    private Set<String> attributesWhoseValuesMustBeExcluded = Set.of("title", "id", "name", "href");
    private Map<String, Function<String,String>> attributesWhoseValuesMustBeProcessed = new HashMap<>();
    private Set<String> attributesToExclude = Set.of("vid", "_odo_bot_taint");

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

                Iterator<Attribute> attrIt = element.attributes().asList().stream().filter(attribute -> !attributesToExclude.contains(attribute.getKey())).toList().iterator();
                while (attrIt.hasNext()){
                    Attribute attr = attrIt.next();
                    sb.append(attr.getKey());
                    if(!attributesWhoseValuesMustBeExcluded.contains(attr.getKey())){
                        sb.append("='");

                        if(attributesWhoseValuesMustBeProcessed.containsKey(attr.getKey())){
                            sb.append(attributesWhoseValuesMustBeProcessed.get(attr.getKey()).apply(attr.getValue()));
                        }else{
                            sb.append(attr.getValue());
                        }

                        sb.append("'");
                    }
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

    private class PruningVisitor implements NodeVisitor {


        @Override
        public void head(Node node, int i) {
            Set<String> tagsToKeep = Set.of("img", "input", "i", "svg", "btn", "button", "iframe", "textarea");
            if (node instanceof Element){
                Element element = (Element) node;
                if(!element.hasText() && //If the element and its children have no text
                        !tagsAreInChildren(element, tagsToKeep) && //And the element does not contain tags marked as toKeep
                        !tagsToKeep.contains(element.tagName())){ //And the element itself isn't a tag to keep.
                    element.remove();
                }
            }

        }

        private boolean tagsAreInChildren(Node node, Set<String> tags){

            List<Node> childrenToCheck = new ArrayList<>();
            node.childNodes().forEach(childrenToCheck::add);

            for (int i = 0; i < childrenToCheck.size(); i++) {
                Node child = childrenToCheck.get(i);
                if(child instanceof Element){
                    Element childElement = (Element) child;
                    if(tags.contains(childElement.tagName())){
                        return true;
                    }
                    childrenToCheck.addAll(child.childNodes());
                }
            }



            return false;

        }
    }

    //
    public static class LabelingVisitor implements NodeVisitor {
        private int counter = 0;
        public Map<Integer, Node> nodeMap = new HashMap<Integer, Node>();
        public Map<Node, Integer> nodeIndex = new HashMap<>();
        @Override
        public void head(Node node, int i) {
            nodeMap.put(counter, node);
            nodeIndex.put(node, counter);
            counter++;
        }

    }

    @Override
    public Future<String> cleanHTML(String input) {
        String html = HTMLCleaningTools.clean(input);

        Document doc = Jsoup.parse(html);

        //Execute visitors that modify the DOM before the labeling visitor
        doc.traverse(new BlankRemovingVisitor());
        doc.traverse(new PruningVisitor());

        //Execute after DOM modifications have been made.
        doc.traverse(new LabelingVisitor());



        return Future.succeededFuture(doc.outerHtml());
    }

    public Future<JsonObject> toNodeLinks(String input){
        String html = HTMLCleaningTools.clean(input);

        Document doc = Jsoup.parse(html);
        doc.traverse(new BlankRemovingVisitor());
        doc.traverse(new PruningVisitor());


        LabelingVisitor labelingVisitor = new LabelingVisitor();
        doc.traverse(labelingVisitor);

        NodeLinksVisitor nodeLinksVisitor = new NodeLinksVisitor(this::nodeToLabel, labelingVisitor.nodeMap, labelingVisitor.nodeIndex, doc, vertx );
        doc.traverse(nodeLinksVisitor);

        return nodeLinksVisitor.getGraphObject();


    }

    public Future<JsonObject> toElementAnnotationQuery(String html, String targetElementXpath) {
        targetElementXpath = Xpath.truncateXpath(targetElementXpath);
        log.info("targetElementXpath: {}",  targetElementXpath );
        Document doc = Jsoup.parse(html);
        Element targetElement = doc.selectXpath(targetElementXpath).first();
        String targetElementOriginalHTML = targetElement.outerHtml();
        String taintValue = UUID.randomUUID().toString();
        targetElement.attr("_odo_bot_taint", taintValue);

        String taintedHtml = HTMLCleaningTools.clean(doc.outerHtml());

        doc = Jsoup.parse(taintedHtml);


        doc.traverse(new BlankRemovingVisitor());
        doc.traverse(new PruningVisitor());
        LabelingVisitor labelingVisitor = new LabelingVisitor();
        doc.traverse(labelingVisitor);

        NodeLinksVisitor nodeLinksVisitor = new NodeLinksVisitor(this::nodeToLabel, labelingVisitor.nodeMap, labelingVisitor.nodeIndex, doc, vertx);
        doc.traverse(nodeLinksVisitor);

        String targetNodeXpath = "//*[@_odo_bot_taint='%s']".formatted(taintValue);
        Element _targetElement = doc.selectXpath(targetNodeXpath).first();
        //This may be different from the original because we do pruning during the cleaning process.
        String cleanedTargetElementXpath = computeXpathNoRoot(_targetElement);

        JsonArray queryNodes =  new JsonArray();

        Element curr =  _targetElement;
        while (curr != null) {
            queryNodes.add(labelingVisitor.nodeIndex.get(curr));
            curr = curr.parent();
        }

        String finalTargetElementXpath = targetElementXpath;
        return nodeLinksVisitor.getGraphObject()
                .compose(nodeLinks->{
                    return Future.succeededFuture(
                    nodeLinks.put("queryNodes", queryNodes)
                            .put("targetElementHTML", targetElementOriginalHTML )
                            .put("targetElementCleanedHTML", _targetElement.outerHtml())
                            .put("targetElementXpath", finalTargetElementXpath)
                            .put("cleanedHTMLTargetElementXpath", cleanedTargetElementXpath)
                    );
        });



    }
}
