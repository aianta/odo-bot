package ca.ualberta.odobot.mind2web;

import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import ca.ualberta.odobot.sqlite.SqliteService;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class DynamicXpathMiner {

    private static final Logger log = LoggerFactory.getLogger(DynamicXpathMiner.class);

    private static final int MAX_HEIGHT = 15;
    private static final int MAX_SIBLINGS_TO_CHECK = 10;

    public static ExecutorService executorService = Executors.newFixedThreadPool(6);


    public static Set<DynamicXPath> mine(Document document, List<String> xpaths){

        Set<DynamicXPath> dynamicXPaths = new HashSet<>();

        for(String xpath: xpaths){
            Optional<DynamicXPath> dynamicXPath = searchForDynamicXpathNear(document, xpath);
            if(dynamicXPath.isPresent()){
                dynamicXPaths.add(dynamicXPath.get());
            }
        }

        return dynamicXPaths;
    }

    private static Optional<DynamicXPath> searchForDynamicXpathNear(Document document, String xpath){

        /**
         * Trim the ending slash off the xpath to the element if one exists.
         *
         * This allows us to later use lastIndexOf("/") with subString() to compute the xpath of any parent leading to the element @ xpath.
         */
        if(xpath.endsWith("/")){
            xpath = xpath.substring(0, xpath.length()-1);
        }

        // handle case where element is not found.
        Elements _elements = document.selectXpath(xpath);
        if(_elements.size() == 0){
            //log.debug("Xpath {} did not resolve to any element in document. ", xpath);
            return Optional.empty();
        }

        Element target = document.selectXpath(xpath).get(0);

        Optional<DynamicXPath>  dynamicXPath = extractDynamicXpathAtElement(target, xpath);

        if(dynamicXPath.isPresent()){
            return dynamicXPath;
        }


        /** If we're here, none of the target element's siblings look the same, so the element is
         *  definitely not the dynamic tag in a dynamic xpath, but it may yet be part of a dynamic xpath's suffix.
         *
         *  Thus, we should search up the DOM tree for an element that could serve as a dynamic tag.
         */
        int height = 0;
        while (target.parent() != null && height < MAX_HEIGHT){
            height++;
            target = target.parent();
            xpath = xpath.substring(0, xpath.lastIndexOf("/")); //This should never return -1, since we only climb up the tree if the target element has another parent.

            dynamicXPath = extractDynamicXpathAtElement(target, xpath);

            if(dynamicXPath.isPresent()){
                return dynamicXPath;
            }
        }

        return Optional.empty();
    }

    /**
     * Assume that the target element is the dynamic tag in a dynamic xpath and try to retrieve a dynamic xpath
     * on the basis of that assumption.
     *
     * That is, find siblings of the target element with the same tag, and recursively check that the subtrees match between
     * the siblings and target element. If they do, then we have a dynamic xpath on our hands. Otherwise return an empty optional.
     *
     * @param target element assumed to be dynamic tag in dynamic xpath.
     * @param xpath the xpath to the target element.
     * @return Optional either containing a dynamic xpath if one was found, empty otherwise.
     */
    private static Optional<DynamicXPath> extractDynamicXpathAtElement(Element target, String xpath){
        //Case 1: Check for siblings.
        Elements siblings = target.siblingElements();

        //If the element has siblings, check to see if their tags match that of the target element.
        List<Element> siblingsToCheck = siblings.stream().filter(sibling->sibling.tagName().equals(target.tagName()))
                .limit(MAX_SIBLINGS_TO_CHECK)
                .filter(sibling->haveCommonSubTree(sibling, target)) //Check if the siblings that share the tag name with the target element have common subtrees with the element.
                .collect(Collectors.toList());

        //If we found such siblings, then we have a dynamic xpath, with the target element as the dynamic tag.
        if(siblingsToCheck.size() > 0) {
            DynamicXPath dynamicXPath = new DynamicXPath();
            dynamicXPath.setDynamicTag(target.tagName());
            dynamicXPath.setPrefix(xpath.substring(0, xpath.lastIndexOf("/")));

            //log.info("Path 2 leaf: {}", pathToLeaf(target));
            dynamicXPath.setSuffix(pathToLeaf(target));

            // Should these have suffixes? Yes! And now they do using path-to-leaf.
            return Optional.of(dynamicXPath);
        }

        return Optional.empty();
    }


    //TODO: Implement this without recursion to avoid stackoverflow on large trees.
    private static boolean haveCommonSubTree(Element a, Element b){

        if (a.children().size() != b.children().size()){
            return false; //If they have different numbers of child elements return false.
        }

        if(a.children().size() == 0 && b.children().size() == 0){
            return true; //Handle trivial case where both A and B are leafs.
        }

        //Otherwise compare the tags of the children.
        Set<String> aChildTags = a.children().stream().map(Element::tagName).collect(Collectors.toSet());
        Set<String> bChildTags = b.children().stream().map(Element::tagName).collect(Collectors.toSet());

        //If both A & B's children have the same tags
        if(aChildTags.containsAll(bChildTags) && bChildTags.containsAll(aChildTags)){

            //Iterate through the children one by one and check to see if they all have common subtrees.
            Iterator<Element> aChildren = a.children().iterator();
            Iterator<Element> bChildren = b.children().iterator();

            while (aChildren.hasNext() && bChildren.hasNext()){
                //If the children do not have common subtrees.
                if(!haveCommonSubTree(aChildren.next(), bChildren.next())){
                    return false; //Return false
                }
            }
            //Otherwise, if all children had common subtrees as well, then A & B have common subtrees.
            return true;

        }else{
            return false;
        }
    }


    private Element nearestLeaf(Element e){
        List<Element> children = e.children();
        Element leaf = e;
        while (children.size() > 0){
            leaf = children.get(0);
            children = leaf.children();
        }

        return leaf;
    }

    public static String pathToLeaf(Element e){
        StringBuilder sb = new StringBuilder();

        List<Element> children = e.children();
        Element leaf = e;
//        sb.append(leaf.tagName());
        while (children.size() > 0){
            leaf = children.get(0);
            sb.append("/"+ leaf.tagName());
            children = leaf.children();
        }

        return sb.toString();
    }

}
