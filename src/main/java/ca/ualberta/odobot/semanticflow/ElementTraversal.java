package ca.ualberta.odobot.semanticflow;

import org.jsoup.helper.Validate;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Based/inspired by on NodeTraversor in JSoup, but stateful. Call next() to proceed further with the traversal.
 *
 * see: https://github.com/jhy/jsoup/blob/master/src/main/java/org/jsoup/select/NodeTraversor.java
 *
 * Useful for traversing two trees simultaneously.
 */
public class ElementTraversal {
    private enum TraversalState{
        DONE, IN_PROGRESS, INIT
    }

    private static final Logger log = LoggerFactory.getLogger(ElementTraversal.class);
    private Element root;
    private Element element;
    private int depth;
    private IElementVisitor visitor;
    private TraversalState state = TraversalState.INIT;

    public interface IElementVisitor{
        void visit(Element e, int depth);
    }

    public static class SimpleVisitor implements  IElementVisitor{

        List<Element> elementList = new ArrayList<>();

        public void visit(Element e, int depth){

            log.info("got: {}@{}", e.tagName(), depth);
            elementList.add(e);
        }

        public List<Element> getElementList(){
            return elementList;
        }
    }

    public ElementTraversal(IElementVisitor visitor, Element root){
        Validate.notNull(root);

        this.root = root;
        this.element = root;
        this.depth = 0;
        this.visitor = visitor;
        this.state = TraversalState.IN_PROGRESS;
    }

    protected void emit(Element e, int depth){
        visitor.visit(e, depth);
    }

    public void traverse() {
        if(state != TraversalState.IN_PROGRESS){
            log.warn("Traversal already complete!");
            return;
        }

        if(element != null){
            //TODO - handle changes to parent by visitor? I don't think that's our use case, but good to know where it would have to be dealt with.
            emit(element, depth);

            if(element.childrenSize() > 0){ //descend
                element = element.child(0);
                depth++;
            }else{
                while(true){
                    assert element != null; // as depth > 0, will have parent
                    if(!(element.nextElementSibling() == null && depth > 0)) break;
                    element = element.parent();
                    depth--;
                }
                if(element == root){
                    log.info("Allegedly done.");
                    state = TraversalState.DONE;
                    return;
                }
                element = element.nextElementSibling();
            }
        }

    }

    public boolean hasNext(){
        return state == TraversalState.IN_PROGRESS;
    }


}
