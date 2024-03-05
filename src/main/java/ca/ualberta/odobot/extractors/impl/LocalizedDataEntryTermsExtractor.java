package ca.ualberta.odobot.extractors.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.semanticflow.model.DataEntry;
import ca.ualberta.odobot.semanticflow.model.InputChange;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.ranking.terms.impl.DistanceToTarget;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;


/**
 * The aim of this extractor is to retrieve terms but in a more restricted way that {@link SimpleDataEntryTermsExtractor}.
 *
 * Preliminary tests show that Data Entry (DE) events are difficult to group together in cases
 * where elements in the background or far away from the text field have changed.
 *
 * IE: different calendar events existed in the background at different points in time.
 * So even though the user is typing into the 'same' text field, because those background/far-away
 * terms are part of the DE feature vector, and because otherwise DE events remain very similar the
 * presence of those distant terms becomes the discriminating factor during clustering.
 *
 * To address this, we want to limit the terms we gather to the context of the text field.
 *
 * For this extractor we'll define the 'context' of the text field as all child, parent, and sibling elements
 * which share the same '<form>' tag parent.
 *
 * Within the <form> context, we also identify other input fields and remove terms closer to those input fields than this input field.
 * Where closer is defined by {@link DistanceToTarget#dijkstra(String domString, Document, Element, Element)}.
 *
 * NOTE: Naturally, this assumes the text field is placed within a <form> tag. This is not guaranteed. If no
 * parent <form> tag is found, we revert back to the normal term extraction strategy.
 *
 */
public class LocalizedDataEntryTermsExtractor extends SimpleDataEntryTermsExtractor implements SemanticArtifactExtractor<DataEntry> {
    private static final Logger log = LoggerFactory.getLogger(LocalizedDataEntryTermsExtractor.class);

    @Override
    public String artifactName() {
        return "localizedTerms";
    }

    @Override
    public Object extract(DataEntry entity, int index, Timeline timeline) {
        try{


        //Get the last input change event for this Data Entry event since it will contain the DOMSnapshot.
        InputChange finalChange = entity.lastChange();
        Document document = finalChange.getDomSnapshot();
        String domString = document.toString();
        Element inputElement = finalChange.getTargetElement();

        log.info(inputElement.outerHtml());

        //Step 1: Find the parent <form> tag from the input element.
        Optional<Element> formOptional = getFormParent(inputElement);
        if (formOptional.isEmpty()){ // If we cannot find a parent form tag, revert to old terms strategy.
            return List.of();
//            return super.extract(entity);
        }


        Element form = formOptional.get();
        //log.info("Collecting terms and fields from form.");
        TermsAndFieldsCollector collector = new TermsAndFieldsCollector();
        NodeTraversor.traverse(collector, form);

        List<Element> formTerms = collector.getTerms();
        List<Element> inputFields = collector.getInputFields();

        //log.info("Computing distances between fields and terms.");
        Map<Element, Integer> inputElementTerms = new HashMap<>();

        formTerms.forEach(t->{
            try{
            LinkedHashMap<Element, Integer> distanceToTerm = new LinkedHashMap<>();
            inputFields.forEach(field->{

                Integer dist = DistanceToTarget.dijkstra(domString, document, t, field);
                //log.info("Distance of {} for {} to {}", dist, t.ownText(), field.outerHtml());
                distanceToTerm.put(field,dist );
            });

            //log.info("# fields processed for term: {}", distanceToTerm.size());
            List<Map.Entry<Element, Integer>> ordered = new ArrayList<>(distanceToTerm.entrySet());
            //log.info("Created ordered: {}", ordered);
            /**
             * Sort in ascending order by value such that the text field the shortest distance away from
             * the term appears first in the list.
             */
            ordered.sort(Comparator.comparingInt(Map.Entry::getValue));


            //log.info("{}", ordered);

            /**
             * If our targetInput element is at the minimum distance from this term when compared to all other inputs,
             * add the term to the result.
             */
            //log.info("Target input field: {}\nordered.get(0):{}\n", inputElement.outerHtml(), ordered.get(0).getKey().outerHtml());
            Integer minDistance = ordered.get(0).getValue();
            Iterator<Map.Entry<Element, Integer>> it = ordered.iterator();
            //This while loop is necessary in cases where multiple fields are at the minimum distance.
            while (it.hasNext()){
                Map.Entry<Element,Integer> curr = it.next();
                //log.info("Record: {}", curr);
                Integer currDistance = curr.getValue();
                if(currDistance == minDistance && curr.getKey().equals(inputElement)){
                    //log.info("Adding {} term for {}", t.ownText(), inputElement.outerHtml());
                    inputElementTerms.put(t, minDistance);
                }
            }

            }catch (Exception e){
                log.error(e.getMessage(), e);
            }

        });

        List<Map.Entry<Element,Integer>> inputElementTermsList = new ArrayList<>(inputElementTerms.entrySet());
        //Finally, sort all the terms that appear closer to our input field than any other field by their distance to our input field.
        inputElementTermsList.sort(Comparator.comparingInt(Map.Entry::getValue));


        List<String> result = inputElementTermsList.stream().map(entry->entry.getKey().ownText()).limit(1).collect(Collectors.toList());
        log.info("LocalizedDataEntryTermsExtractor result: {}", result);
        return result;
        }catch (Exception e){
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private class TermsAndFieldsCollector implements NodeVisitor{

        List<Element> terms = new ArrayList<>();
        List<Element> inputFields = new ArrayList<>();


        @Override
        public void head(Node node, int depth) {
            try{
                //If this node is a non-input element with non-empty ownText
                if(node instanceof Element &&
                        !((Element)node).tagName().equals("input") &&
                        !((Element)node).ownText().isBlank()

                ){
                    log.info("Added term {}", ((Element)node).ownText());
                    //Add it to our terms list.
                    terms.add((Element)node);
                }

                //If this node is an input element.
                if(node instanceof Element &&
                        ((Element)node).tagName().equals("input")){
                    log.info("Added field {}", ((Element)node).outerHtml());
                    //Add it to our input fields list.
                    inputFields.add((Element)node);
                }
            }catch (Exception e){
                log.error(e.getMessage(), e);
            }


        }

        @Override
        public void tail(Node node, int depth) {

        }

        public List<Element> getTerms() {
            return terms;
        }

        public List<Element> getInputFields() {
            return inputFields;
        }
    }

    /**
     * Iterates through an element's parent list until the first 'form' tag parent is found
     * if it exists.
     *
     * @param inputElement
     * @return The parent form element, if it exists.
     */
    public Optional<Element> getFormParent(Element inputElement){

        Elements parents = inputElement.parents();
        Iterator<Element> it = parents.iterator();
        while (it.hasNext()){
            Element curr = it.next();
            log.info("Looking for 'form' currently: {}", curr.tagName());
            if(curr.tagName().equals("form")){
                return Optional.of(curr);
            }
        }

        return Optional.empty();
    }
}
