package ca.ualberta.odobot.semanticflow;

import org.jsoup.nodes.Element;

public class MergingTraversal extends ElementTraversal{

    /*
     * Merging operation creates a new tree with elements from
     * both source and target.
     */
    public enum TraversalOrigin {
        SOURCE, //The elements to merge
        TARGET  //The target to merge them into.
    }

    private TraversalOrigin origin;
    private IMergingVisitor visitor;

    interface IMergingVisitor{
        void visit(Element e, int depth, MergingTraversal.TraversalOrigin origin);
    }

    public MergingTraversal(IMergingVisitor visitor, Element root, TraversalOrigin origin) {
        super(null, root);
        this.origin = origin;
        this.visitor = visitor;
    }

    @Override
    protected void emit(Element e, int depth) {
        visitor.visit(e, depth, origin);
    }
}
