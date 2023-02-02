package ca.ualberta.odobot.semanticflow;

import io.vertx.core.json.JsonObject;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.print.Doc;
import java.util.Iterator;
import java.util.Map;

/**
 * Container class for algorithm merging two DOM trees together.
 */
public class MergingAlgorithm {

    public static final Logger log = LoggerFactory.getLogger(MergingAlgorithm.class);



    private static class MergingVisitor implements MergingTraversal.IMergingVisitor {
        private static final Logger vlog = LoggerFactory.getLogger(MergingVisitor.class);

        Document result = new Document("");

        int sourceTraversalIndex = 0;
        int targetTraversalIndex = 0;

        Element lastSourceElement = null;
        Element lastTargetElement = null;
        Element lastResultElement = null;

        int lastSourceDepth = -1;
        int lastTargetDepth = -1;
        int lastResultDepth = -1;

        @Override
        public void visit(Element e, int depth, MergingTraversal.TraversalOrigin origin) {

            switch (origin){
                case SOURCE -> {
                    sourceTraversalIndex++;
                    lastSourceElement = e;
                    lastSourceDepth = depth;
                }
                case TARGET -> {
                    targetTraversalIndex++;
                    lastTargetElement = e;
                    lastTargetDepth = depth;
                }
            }

            if(lastSourceElement != null && lastTargetElement != null){
                merge();
                lastSourceElement = null;
                lastTargetElement = null;
                lastSourceDepth = -1;
                lastTargetDepth = -1;
            }
        }

        private void merge(){
            /**
             * Two cases:
             * 1. The lastSourceElement and the lastTargetElement are the same.
             * 2. The lastSourceElement and the lastTargetElement are not the same.
             */
            if(isEqual(lastSourceElement, lastTargetElement)){

//                Element resultElement = result.createElement(lastSourceElement.tagName());
//                Iterator<Attribute> it = lastSourceElement.attributes().iterator();
//                while (it.hasNext()){
//                    Attribute sourceAttribute = it.next();
//                    resultElement.attr(sourceAttribute.getKey(), sourceAttribute.getValue());
//                }
//
//                //Handle the case where this is the first element in the result.
//                if(result.getAllElements().isEmpty()){
//                    result.append(resultElement.html());
//                }else{
//                    if (lastSourceDepth == )
//                }
//
//                lastResultElement = resultElement;
//                lastResultDepth = lastSourceDepth;
//                if(lastResultDepth != lastTargetDepth){
//                    log.warn("depths do not align. Source: {} Target: {} Result: {}", lastSourceDepth, lastTargetDepth, lastResultDepth);
//                }
                if(result.getAllElements().isEmpty()){
                    result.append(lastSourceElement.clone().html());
                }else{
                    lastSourceElement.clone().appendTo(lastResultElement);
                }

                lastResultElement = lastSourceElement;

            }else{
                if(result.getAllElements().size() == 1){
                    result = Document.createShell("");
                    Element temp = lastTargetElement.clone();
                    lastSourceElement.clone().appendTo(result.root());
                    temp.appendTo(result.root());
                    lastResultElement = temp;
                }else{
                    lastSourceElement.clone().appendTo(lastResultElement);
                    lastTargetElement.clone().appendTo(lastResultElement);
                }



            }
        }

        private boolean isEqual(Element e1, Element e2){
//            //If they are different tags they're not the same.
//            if(!e1.tagName().equals(e2.tagName())) return false;
//
//            //If they have ids then they should match.
//            if(!e1.id().isEmpty() && !e2.id().isEmpty() && !e1.id().equals(e2.id())) return false;
//
//            /**
//             * If element1 and element 2 share an attribute, and the value of that shared attribute isn't equal. They are
//             * not the same.
//             */
//            Iterator<Attribute> it = e1.attributes().iterator();
//            while (it.hasNext()){
//                Attribute e1Attribute = it.next();
//                if(e2.attributes().hasKey(e1Attribute.getKey()) && !e2.attr(e1Attribute.getKey()).equals(e1Attribute.getValue())) return false;
//            }
//
//            /* If they have a different number of attributes they're not the same.
//             * TODO Uhhhh.... I'm not sure this assumption holds. I feel like it might be possible
//             * for a web framework to add and remove attributes from the same tag in some cases. In which case we'd like
//             * to consider them the same.
//             */
//            if(e1.attributesSize() != e2.attributesSize()){
//                vlog.warn("element inequality was decided by attribute size.");
//                log.warn("Element1 attributes: \n{}\nElement2 attributes:{}\n", Utils.elementAttributesToJson(e1).encodePrettily(), Utils.elementAttributesToJson(e2).encodePrettily());
//                return false;
//            }
//
//
//            return true;

            return e1.equals(e2);
        }

        public Document getResult(){
            return result;
        }
    }

    public static Document merge(Document tree1, Document tree2){

        /**
         * Idea: perform a simultaneous preorder traversal on both trees at the same time
         * and add everything encountered along the way to the resulting graph.
         */
        Document result = new Document(tree1.baseUri()); //Create the result document using tree1's base uri

        //Initialize Merging visitors and traversals for tree1 and tree2
        MergingVisitor visitor = new MergingVisitor();

        ElementTraversal traversal1 = new MergingTraversal(visitor, tree1.root(), MergingTraversal.TraversalOrigin.TARGET);
        ElementTraversal traversal2 = new MergingTraversal(visitor, tree2.root(), MergingTraversal.TraversalOrigin.SOURCE);

        /**
         * Several cases here:
         * 1. traversal1 (tree1) has more elements than traversal2 (tree2)
         * 2. traversal1 (tree1) has fewer elements than traversal2 (tree2)
         * 3. traversal1 and traversal 2 have the same number of elements.
         */

        while (traversal1.hasNext() && traversal2.hasNext()){
            traversal1.traverse();
            traversal2.traverse();
        }

        while (traversal1.hasNext()){
            traversal1.traverse();
        }

        while (traversal2.hasNext()){
            traversal2.traverse();
        }

        return visitor.getResult();

    }



}
