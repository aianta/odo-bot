package ca.ualberta.odobot.semanticflow.ranking.terms.impl;

import ca.ualberta.odobot.semanticflow.extraction.terms.TermExtractionStrategy;
import ca.ualberta.odobot.semanticflow.ranking.terms.TermRankingStrategy;
import ca.ualberta.odobot.semanticflow.model.AbstractArtifact;
import edu.stanford.nlp.ling.CoreLabel;
import org.apache.lucene.search.FieldComparator;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Produces a list of string terms ordered by their distance to the Artifact's target element.
 *
 * @author Alexandru Ianta
 */
public class DistanceToTarget implements TermRankingStrategy<AbstractArtifact> {
    private static final Logger log = LoggerFactory.getLogger(DistanceToTarget.class);

    // https://www.ling.upenn.edu/courses/Fall_2003/ling001/penn_treebank_pos.html
    private static final Set<String> ALLOWED_PARTS_OF_SPEECH = Set.of(
        "CC","CD","DT","EX","FW","IN","JJ","JJR","JJS","LS","MD","NN","NNS","NNP","NNPS","PDT",
            "POS","PRP","PRP$","RB","RBR","RBS","RP","SYM","TO","UH","VB","VBD","VBG","VBN","VBP",
            "VBZ","WDT","WP","WP$","WRB"
    );

    /**
     * An enum to choose different options for handling multiple elements matching
     * extracted tokens when computing the distance (rank) for the token.
     */
    public enum MultiElementOptions{
        MEAN, //Take the average distance of the elements
        MAX,  //Take the maximum distance of the elements
        MIN   //Take the minimum distance of the elements
    }

    private MultiElementOptions multiElementOptions = MultiElementOptions.MIN;

    public void setMultiElementOptions(MultiElementOptions multiElementOptions) {
        this.multiElementOptions = multiElementOptions;
    }

    @Override
    public List<String> getTerms(AbstractArtifact artifact, TermExtractionStrategy extractionStrategy) {


        Element targetElement = artifact.getTargetElement();
        Document dom = artifact.getDomSnapshot();
        Element body = dom.body(); //TODO I think we can safely exclude the head/meta.... try it this way for now.
        log.debug("targetElement: {}", targetElement);

        List<CoreLabel> terms = extractionStrategy.extractTerms(body);
        log.debug("terms size: {}", terms.size());
        log.debug("'Event' count: {}",terms.stream().filter(term->term.word().equals("Event")).count());

        List<RankedTerm> result = terms.stream()
                .filter(term->ALLOWED_PARTS_OF_SPEECH.contains(term.tag()))
                .map(term->{
            log.debug("Looking for: {}", term.word());

            Elements elements = body.getElementsMatchingOwnText(term.word());
            log.debug("Found {} elements", elements.size());

            if(elements.size() > 1){
                return switch (multiElementOptions){
                    case MIN -> new RankedTerm(term,
                                    elements.stream()
                                            .mapToInt(match->dijkstra(dom, targetElement, match))
                                            .min().getAsInt()
                                );
                    case MAX -> new RankedTerm(term,
                                    elements.stream()
                                            .mapToInt(match->dijkstra(dom, targetElement, match))
                                            .max().getAsInt()
                                );
                    case MEAN -> new RankedTerm(term,
                                    elements.stream()
                                            .mapToInt(match->dijkstra(dom, targetElement, match))
                                            .average().getAsDouble()
                            );
                };
            }

            if(elements.size() == 1){
                return new RankedTerm(term, dijkstra(dom, targetElement, elements.first()));
            }

            log.warn("About to return a null ranked term, this happens when we cannot find elements containing " +
                    "a particular token. Could be an indicator of poor tokenization/extraction strategy if it happens too often.");
            return null;
        }).filter(Objects::nonNull).collect(Collectors.toList());

        result.sort(new Comparator<RankedTerm>() {
            @Override
            public int compare(RankedTerm o1, RankedTerm o2) {
                if(o1.ranking() < o2.ranking()){
                    return -1;
                }
                if(o1.ranking() > o2.ranking()){
                    return 1;
                }

                return 0;
            }
        });

        result.forEach(term->{
            log.debug("{} - {}", term.term().word(), term.ranking());
        });


        return result.stream().map(value->value.term().word()).collect(Collectors.toList());
    }


    /**
     * https://en.wikipedia.org/wiki/Dijkstra's_algorithm
     * @param document
     * @param src
     * @param tgt
     */
    private Integer dijkstra(Document document, Element src, Element tgt){

        /**
         * Initalize Dijkstra
         */
        Elements vertices = document.getAllElements();
        Map<Element, Integer> dist = new HashMap<>();
        Map<Element, Element> prev = new HashMap<>();
        vertices.forEach(v->{
            dist.put(v, Integer.MAX_VALUE-1);
            prev.put(v, null);
        });
        dist.put(src, 0);

        PriorityQueue<Element> q = new PriorityQueue<>(vertices.size(), new Comparator<Element>() {
            @Override
            public int compare(Element o1, Element o2) {
                int dist1 = dist.get(o1);
                int dist2 = dist.get(o2);
                return dist1 - dist2;
            }
        });

        vertices.forEach(q::add);

        while (!q.isEmpty()){
            Element u = q.poll();

            if(u.equals(tgt)){
                return dist.get(u);
            }

            neighbours(u).forEach(v->{
                int alt = dist.get(u) + 1; //All edges have same weight 1.
                if(alt < dist.get(v)){
                    dist.put(v, alt);
                    prev.put(v, u);
                    /** Have to remove and re-add v because {@link PriorityQueue} only updates order
                     *  on insertion.
                     *  https://stackoverflow.com/questions/1871253/updating-java-priorityqueue-when-its-elements-change-priority
                     */
                    q.remove(v);
                    q.add(v);
                }
            });

        }

        log.warn("Dijkstra's failed, you should probably panic...");
        return null;

    }

    private Elements neighbours(Element e){
        Elements result = new Elements();
        if(e.hasParent()){
            result.add(e.parent());
        }
        result.addAll(e.children());
        return result;
    }
}
