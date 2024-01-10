package ca.ualberta.odobot.domsequencing;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeVisitor;

import java.util.*;

public class DOMVisitor implements NodeVisitor {

    List<String> texts = new ArrayList<>();
    DOMSequence sequence = new DOMSequence();
    CSSManifest cssManifest = new CSSManifest();

    DirectlyFollowsManifest directlyFollowsManifest = new DirectlyFollowsManifest();

    @Override
    public void head(Node node, int depth) {
        if(node instanceof Element){
            Element element = (Element) node;
            sequence.add(new DOMSegment(element.tagName(), element.className()));
            cssManifest.catalogElement(element);
        }
    }

    public void tail(Node node, int depth){
        //Tabulate which classes appear in child elements of parent classes
        //I think this needs to be done in the 'tail' method to avoid double counting.
        //But I may be wrong about that, TODO - reason this out please, we need to be confident.
        if(node instanceof Element){
            Element element = (Element) node;
            directlyFollowsManifest.catalogElement(element);
        }
    }

    public DOMSequence getSequence(){
        return sequence;
    }

    public CSSManifest getCssManifest(){
        return cssManifest;
    }

    public DirectlyFollowsManifest getDirectlyFollowsManifest(){
        return directlyFollowsManifest;
    }
}
