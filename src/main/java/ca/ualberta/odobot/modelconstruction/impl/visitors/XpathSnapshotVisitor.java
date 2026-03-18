package ca.ualberta.odobot.modelconstruction.impl.visitors;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeVisitor;

import static ca.ualberta.odobot.semanticflow.Utils.computeXpathNoRoot;

public class XpathSnapshotVisitor implements NodeVisitor {
    @Override
    public void head(Node node, int i) {
        if(node instanceof Element){
            node.attr("oxp", computeXpathNoRoot((Element)node));
        }
    }

    @Override
    public void tail(Node node, int depth) {
        NodeVisitor.super.tail(node, depth);
    }
}
