package ca.ualberta.odobot.semanticflow.ranking.terms.impl;

import ca.ualberta.odobot.semanticflow.extraction.terms.TermExtractionStrategy;
import ca.ualberta.odobot.semanticflow.extraction.terms.annotators.EnglishWordAnnotator;
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
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final Pattern numbers = Pattern.compile("[0-9]+");
    private static final Pattern logicalTextRegion = Pattern.compile("(?<=[>])([a-zA-Z0-9?!;.,\\\"\\\"\\(\\)\\s]+)(?=[<])");

    private static final DijkstraCache cache = new DijkstraCache();

    private BiFunction<Element,String, Elements> matchingFunction;

    /**
     * An enum to choose different options for handling multiple elements matching
     * extracted tokens when computing the distance (rank) for the token.
     */
    public enum MultiElementOptions{
        MEAN, //Take the average distance of the elements
        MAX,  //Take the maximum distance of the elements
        MIN   //Take the minimum distance of the elements
    }

    public enum SourceFunction{
        TEXT((artifact)->artifact.getDomSnapshot().body().text());

         Function<AbstractArtifact, String> function;

         SourceFunction(Function<AbstractArtifact, String> function){
             this.function = function;
         }

         public Function<AbstractArtifact, String> getFunction(){
             return function;
         }
    }

    public enum MatchingFunction{
        OWN_TEXT(
                (element,regex)->element.getElementsMatchingOwnText(Pattern.quote(regex))
        );

        public BiFunction<Element,String,Elements> function;
        MatchingFunction(BiFunction<Element,String, Elements> function){
            this.function = function;
        }

        public BiFunction<Element,String,Elements> getFunction(){
            return function;
        }
    }

    private MultiElementOptions multiElementOptions = MultiElementOptions.MIN;

    public BiFunction<Element,String, Elements> getMatchingFunction() {
        return matchingFunction;
    }

    /**
     * The matching function is used to return a set of elements from the body of the document which match
     * the field we're interested in using.
     *
     * For example, if the source function returns the text in the body {@link SourceFunction#TEXT}
     * of the elements, the corresponding matching function would use {@link  org.jsoup.nodes.Element#getElementsMatchingOwnText(String)} to
     * retrieve element to compute the distance to.
     * @param matchingFunction
     */
    public void setMatchingFunction(BiFunction<Element,String, Elements> matchingFunction) {
        this.matchingFunction = matchingFunction;
    }

    public void setMultiElementOptions(MultiElementOptions multiElementOptions) {
        this.multiElementOptions = multiElementOptions;
    }

    /**
     *
     * @param artifact
     * @param extractionStrategy
     * @param source a function that returns the string to extract terms from
     * @param <T>
     * @return
     */
    @Override
    public <T extends AbstractArtifact> List<String> getTerms(T artifact, TermExtractionStrategy extractionStrategy, Function<T, String> source) {
        if(matchingFunction == null){
            log.error("Cannot get terms! Matching function is not set!");
            return List.of();
        }
        cache.printStats();
        Element targetElement = artifact.getTargetElement();
        Document dom = artifact.getDomSnapshot();
        String domString = dom.toString();

        Element body = dom.body();

        log.debug("targetElement: {}", targetElement);


        //Term extraction
//        List<String> terms = extractionStrategy.extractTerms(
//                artifact, //The artifact to extract terms from
//                (a)->a.getDomSnapshot().body().text(), //The source function to apply to the artifact to get a raw string to extract from.
//                //Transformation to be applied to resulting CoreLabel
//                coreLabel -> {
//                    if(coreLabel.tag().equals("SYM")){ //Handle case where we're looking for '+' or some other symbol.
//                        coreLabel.setWord("\\"+coreLabel.word());
//                    }
//                    return coreLabel;
//                }
//                ,
//                (term)->ALLOWED_PARTS_OF_SPEECH.contains(term.tag()) //Filtering to be applied to the core label results
//        );

        //List<String> terms = getLogicalTextRegions(body.outerHtml());
        List<String> terms = getTopNLogicalTextRegions(targetElement, 10);


        log.debug("terms size: {}", terms.size());
        log.debug("'Event' count: {}",terms.stream().filter(term->term.equals("Event")).count());


        List<RankedTerm> result = terms.stream()
                .map(term->{
            log.debug("Looking for: {}", term);

            Elements elements = matchingFunction.apply(body, term);

            log.debug("Found {} elements", elements.size());

            if(elements.size() > 1){
                return switch (multiElementOptions){
                    case MIN -> new RankedTerm(term,
                                    elements.stream()
                                            .mapToInt(match->cachedOrComputedDistance(domString, dom, targetElement, match))
                                            .min().getAsInt()
                                );
                    case MAX -> new RankedTerm(term,
                                    elements.stream()
                                            .mapToInt(match->cachedOrComputedDistance(domString, dom, targetElement, match))
                                            .max().getAsInt()
                                );
                    case MEAN -> new RankedTerm(term,
                                    elements.stream()
                                            .mapToInt(match->cachedOrComputedDistance(domString, dom, targetElement, match))
                                            .average().getAsDouble()
                            );
                };
            }

            if(elements.size() == 1){
                return new RankedTerm(term, cachedOrComputedDistance(domString, dom, targetElement, elements.first()));
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
            log.debug("{} - {}", term.term(), term.ranking());
        });


        return result.stream().map(value->value.term()).collect(Collectors.toList());
    }

    private static Integer cachedOrComputedDistance(String domString, Document document, Element source, Element target){
        Optional<Integer> cachedDistance = cache.getDistance(domString, source, target);
        if(cachedDistance.isEmpty()){
            return dijkstra(domString, document, source, target);
        }else{
            return cachedDistance.get();
        }
    }

    /**
     * https://en.wikipedia.org/wiki/Dijkstra's_algorithm
     * @param document
     * @param src
     * @param tgt
     */
    public static Integer dijkstra(String domString, Document document, Element src, Element tgt){

        /**
         * Initialize Dijkstra
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
                //Cache all non-infinite distances from this source element
                dist.entrySet().stream()
                        .filter(entry->entry.getValue() < Integer.MAX_VALUE - 1)
                        .forEach(entry->{
                            cache.cacheDistance(domString, src, entry.getKey(), entry.getValue());
                            //Shortest distances are commutative in non-directed graphs, so add target to source values as well
                            cache.cacheDistance(domString, entry.getKey(), src, entry.getValue());
                        });

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

    private static Elements neighbours(Element e){
        Elements result = new Elements();
        if(e.hasParent()){
            result.add(e.parent());
        }
        result.addAll(e.children());
        return result;
    }

    /**
     * Takes the target element then extracts logical text regions from it.
     * Once more than n text regions are found, they are returned. Otherwise,
     * the method moves to the parent element of the target iteratively until
     * more than n terms are found.
     *
     * NOTE: more than N text regions may be returned.
     *
     * @param targetElement target element from which to start searching for logical text regions
     * @param n the minimum number of text regions before returning.
     * @return a list of logical text regions whose size is greater than n.
     */
    private static List<String> getTopNLogicalTextRegions(Element targetElement, int n){

        Element curr = targetElement;
        List<String> termsInElement = new ArrayList<>();
        while (termsInElement.size() < n && curr != null){
            termsInElement = getLogicalTextRegions(curr.html());
            curr = curr.parent();
        }
        log.info("Sought {} text regions, got {}", n, termsInElement.size());
        return termsInElement;
    }

    private static List<String> getLogicalTextRegions(String html){
        List<String> results = new ArrayList<>();
        Matcher matcher = logicalTextRegion.matcher(html);

        while (matcher.find()){
            String content = matcher.group(0);
            content = content.trim();
            if(content.isEmpty() | content.isBlank() | content.length() == 1 | numbers.matcher(content).matches()){
                continue; //Don't add blank/empty strings, or strings of length 1.
            }
            results.add(content);
        }

        return results;
    }
}
