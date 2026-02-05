package ca.ualberta.odobot.cleaner.impl.visitors;

import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

public class BlankRemovingVisitor implements NodeVisitor {

    @Override
    public void head(Node node, int i) {
        if (node instanceof TextNode) {
            TextNode textNode = (TextNode) node;
            // Remove blank text nodes
            if (textNode.isBlank()) {
                node.remove();
            }else {
                //Clean up white-space
                ((TextNode) node).text(((TextNode) node).text().trim());
            }
        }
    }

    @Override
    public void tail(Node node, int depth) {
        NodeVisitor.super.tail(node, depth);
    }
}