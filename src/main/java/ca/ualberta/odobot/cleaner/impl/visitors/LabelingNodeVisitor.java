package ca.ualberta.odobot.cleaner.impl.visitors;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

public class LabelingNodeVisitor implements NodeVisitor {
    private static int COUNTER = 0;
    @Override
    public void head(Node node, int i) {
        if (node instanceof Element){
            node.attr("vid", Integer.toString(++COUNTER));
        }

        if (node instanceof TextNode){
            TextNode textNode = (TextNode) node;
            if (textNode.isBlank()){
                textNode.remove();
                return;
            }
            ((TextNode) node).text(((TextNode) node).text() + "["+(++COUNTER)+"]");
        }


    }

    @Override
    public void tail(Node node, int depth) {
        NodeVisitor.super.tail(node, depth);
    }
}