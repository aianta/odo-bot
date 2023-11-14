package ca.ualberta.odobot.domsequencing;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeVisitor;

public class DOMVisitor implements NodeVisitor {

    DOMSequence sequence = new DOMSequence();

    @Override
    public void head(Node node, int depth) {
        if(node instanceof Element){
            Element element = (Element) node;
            sequence.add(new DOMSegment(element.tagName(), element.className()));
        }
    }

    public DOMSequence getSequence(){
        return sequence;
    }
}
