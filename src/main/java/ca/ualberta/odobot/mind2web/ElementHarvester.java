package ca.ualberta.odobot.mind2web;

import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ElementHarvester {

    private static final Logger log = LoggerFactory.getLogger(ElementHarvester.class);

    public static List<Element> getElementsByXpaths(Document document, List<String> xpaths){
        return xpaths.stream()
                .map(xpath->getElementsByXpath(document, xpath))
                .collect(ArrayList::new, (list,o)->list.addAll(o), ArrayList::addAll);
    }

    private static List<Element> getElementsByXpath(Document document, String xpath){
        return document.selectXpath(xpath).stream().collect(Collectors.toList());
    }

    public static List<Element> getElementsByDynamicXpaths(Document document, List<DynamicXPath> dynamicXPaths){
        return dynamicXPaths.stream()
                .map(dxpath->getElementsByDynamicXpath(document, dxpath))
                .collect(ArrayList::new, (list,o)->list.addAll(o), ArrayList::addAll);
    }

    private static List<Element> getElementsByDynamicXpath(Document document, DynamicXPath dynamicXPath){

        return document.selectXpath(dynamicXPath.getPrefix()).stream().collect(Collectors.toList());

    }
}
