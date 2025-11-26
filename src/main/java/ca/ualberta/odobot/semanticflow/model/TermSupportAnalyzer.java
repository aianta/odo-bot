package ca.ualberta.odobot.semanticflow.model;

import ca.ualberta.odobot.semanticflow.extraction.terms.annotators.EnglishWordAnnotator;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A construct for computing the support a term has at a specific point in a timeline.
 */
public class TermSupportAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(TermSupportAnalyzer.class);

    //Constructs from which the term originates.
    private DbOps originDbOps;
    private NetworkEvent originNetworkEvent;

    private ClickEvent nearestPreceedingClickEvent;

    private StanfordCoreNLP nlpPipeline;

    //Contains details about how support was computed for audit/debugging/analysis purposes. NOTE: is updated on every getTermSupport()
    private JsonArray supportDetailsHistory = new JsonArray();

    public static class TermSupportAnalyzerBuilder{
        private final DbOps dbOps;
        private final NetworkEvent networkEvent;

        private ClickEvent nearestPreceedingClickEvent;


        public TermSupportAnalyzerBuilder(DbOps originOps, NetworkEvent originNetworkEvent){
            dbOps = originOps;
            networkEvent = originNetworkEvent;
        }

        public TermSupportAnalyzerBuilder nearestPreceedingClickEvent(ClickEvent event){
            this.nearestPreceedingClickEvent = event;
            return this;
        }

        public TermSupportAnalyzer build(){
            TermSupportAnalyzer result = new TermSupportAnalyzer();
            result.originDbOps = this.dbOps;
            result.originNetworkEvent = this.networkEvent;
            result.nearestPreceedingClickEvent = this.nearestPreceedingClickEvent;
            return result;
        }

    }

    public TermSupportAnalyzer(){
        Properties properties = new Properties();
        properties.setProperty("englishWords.wordnetHome", "C:\\Program Files (x86)\\WordNet\\2.1");
        properties.setProperty("customAnnotatorClass.englishWords", "ca.ualberta.odobot.semanticflow.extraction.terms.annotators.EnglishWordAnnotator");
        properties.setProperty("annotators", "tokenize,ssplit,pos,lemma,englishWords");

        this.nlpPipeline = new StanfordCoreNLP(properties);
    }

    public void resetSupportDetailsHistory(){
        supportDetailsHistory = new JsonArray();
    }


    public double getNGramSupport(List<String> ngram){

        ngram = preprocessNgram(ngram); //lemmatize lowercase

        try{

            Double pathSupport = computeNetworkPathSupport(ngram);
            log.info("pathSupport: {}", pathSupport);

            Double documentUrlSupport = null;
            if(originNetworkEvent.getDocumentUrl() != null){
                documentUrlSupport = computeDocumentURLSupport(ngram);
                log.info("documentUrlSupport: {}", documentUrlSupport);
            }

            Double preceedingClickEventSupport = null;
            if(nearestPreceedingClickEvent !=null){
                //Accumulate support from baseURI
                Double clickBaseURISupport = computeBaseURISupport(ngram);
                log.info("clickBaseURISupport: {}", clickBaseURISupport);
                preceedingClickEventSupport = clickBaseURISupport;

                //Accumulate support from cssClassTerms
                if(nearestPreceedingClickEvent.getSemanticArtifacts() != null && nearestPreceedingClickEvent.getSemanticArtifacts().containsKey("cssClassTerms")){
                    Double cssClassTermSupport = computeCssClassTermsSupport(ngram);
                    log.info("cssClassTermSupport: {}", cssClassTermSupport);
                    preceedingClickEventSupport += cssClassTermSupport;
                }

                //Accumulate support from terms
                if(nearestPreceedingClickEvent.getSemanticArtifacts() != null && nearestPreceedingClickEvent.getSemanticArtifacts().containsKey("terms")){
                    Double termsSupport = computeTermsSupport(ngram);
                    log.info("termsSupport: {}", termsSupport);
                    preceedingClickEventSupport += termsSupport;

                }


            }

            double totalSupport = pathSupport;
            if(documentUrlSupport != null){
                totalSupport += documentUrlSupport;
            }
            if(preceedingClickEventSupport != null){
                totalSupport += preceedingClickEventSupport;
            }

            log.info("total support: {}", totalSupport);

            return totalSupport;

        } catch (MalformedURLException e) {
            log.error("Bad document URL! {}", originNetworkEvent.getDocumentUrl());
            throw new RuntimeException(e);
        }

    }

    private double computeTermsSupport(List<String> ngrams){
        double result = 0.0;
        JsonArray textTerms = nearestPreceedingClickEvent.getSemanticArtifacts().getJsonArray("terms");
        ListIterator<String> it = textTerms.stream().map(o->(String)o).collect(Collectors.toList()).listIterator();
        while (it.hasNext()){
            int index = it.nextIndex();
            String textTermEntry = it.next();

            double support = getSupport2(ngrams, textTermEntry);
            double supportToAdd = (((double) textTerms.size() - (double) index)/(double) textTerms.size())* support;
            result += supportToAdd;
        }

        return result;
    }

    private double computeNetworkPathSupport(List<String> ngram){
        String path = originNetworkEvent.getPath();
        String [] pathSplit = path.split("/");
        String pathInput = Arrays.stream(pathSplit).filter(part->!part.equals("*")).collect(StringBuilder::new, (sb, s)->sb.append(s + " ") , StringBuilder::append).toString();
        Double pathSupport = getSupport2(ngram, pathInput);
        return pathSupport;
    }

    private double computeDocumentURLSupport(List<String> ngram) throws MalformedURLException {
        String documentUrl = originNetworkEvent.getDocumentUrl();
        String documentPath = new URL(documentUrl).getPath();
        String [] documentPathSplit = documentPath.split("/");
        String documentPathInput = Arrays.stream(documentPathSplit).collect(StringBuilder::new,(sb,s)->sb.append(s + " "),StringBuilder::append).toString();
        Double result = getSupport2(ngram, documentPathInput);
        return result;
    }

    private double computeBaseURISupport(List<String> ngram) throws MalformedURLException {
        String baseUri = nearestPreceedingClickEvent.getBaseURI().toString();
        String baseUriPath = new URL(baseUri).getPath();
        String [] baseUriSplit = baseUriPath.split("/");
        String baseUriInput = Arrays.stream(baseUriSplit).filter(part->!part.equals("*")).collect(StringBuilder::new, (sb,s)->sb.append(s + " "), StringBuilder::append).toString();

        Double clickBaseURISupport = getSupport2(ngram, baseUriInput);
        return clickBaseURISupport;
    }

    private double computeCssClassTermsSupport(List<String> ngram){
        double result = 0.0;
        JsonArray cssClassTerms = nearestPreceedingClickEvent.getSemanticArtifacts().getJsonArray("cssClassTerms");
        Iterator<Object> cssClassTermIterator = cssClassTerms.iterator();
        while (cssClassTermIterator.hasNext()){
            String cssClassTermEntry = (String) cssClassTermIterator.next();
            result += getSupport2(ngram, cssClassTermEntry );
        }

        return result;
    }

    /**
     * For a given term, this function returns how much 'support'
     * there is for the term.
     *
     * Usually 'support' is some function of the number of matching instances that
     * can be found for a term and the 'closeness' of those instances to the origin
     * point of the DbOps from which the term is sourced.
     *
     * @param term The term to find support for. This function assumes the term is a table name sans schema eg 'calendar_events', 'assignment_override_students', 'quizzes'.
     * @return
     */
    public Double getTermSupport(String term){
        JsonObject supportDetails = new JsonObject(); // Reset the support details object
        supportDetails.put("inputTerm", term);

        log.info("Getting support for: {}", term);
        try{
            term = term.toLowerCase();
            List<String> lemmatizedTerms = computeLemmatizedTermTokens(term);
            log.info("lemmatized terms: {}", lemmatizedTerms.toString());
            supportDetails.put("lemmatizedInputTerms", lemmatizedTerms.stream().collect(JsonArray::new, JsonArray::add, JsonArray::add));

            /* Begin by looking for instances of the term in the origin network event.
             * Places to search:
             *  -> path
             *  -> documentUrl (if not null)
             */
            supportDetails.put("originNetworkEvent", originNetworkEvent.getId().toString());

            String path = originNetworkEvent.getPath();
            supportDetails.put("path", path);

            String [] pathSplit = path.split("/");

            String pathInput = Arrays.stream(pathSplit).filter(part->!part.equals("*")).collect(StringBuilder::new, (sb, s)->sb.append(s + " ") , StringBuilder::append).toString();
            supportDetails.put("pathInput", pathInput);

            log.info("pathInput: {}", pathInput);
            Double pathSupport = getSupport(lemmatizedTerms, pathInput);
            supportDetails.put("pathSupport", pathSupport);


            Double documentUrlSupport = null;
            supportDetails.put("documentUrlExists", false);
            if(originNetworkEvent.getDocumentUrl() != null){
                supportDetails.put("documentUrlExists", true);
                String documentUrl = originNetworkEvent.getDocumentUrl();
                supportDetails.put("documentUrl", documentUrl);
                URL documentUrlObject = new URL(documentUrl);
                String documentPath = documentUrlObject.getPath();
                supportDetails.put("documentPath", documentPath);
                String [] docPathSplit = documentPath.split("/");
                String docPathInput = Arrays.stream(docPathSplit).collect(StringBuilder::new,(sb,s)->sb.append(s + " "),StringBuilder::append).toString();
                supportDetails.put("documentPathInput", docPathInput);
                documentUrlSupport = getSupport(lemmatizedTerms, docPathInput);
                supportDetails.put("documentUrlSupport", documentUrlSupport);
            }

            /* If available look for instances of the term in the nearest preceeding click event.
             * Places to search:
             *      -> cssClassTerms
             *      -> baseURI
             *      -> terms*     * make the score given for matches here proportional to the distance of the matching term.
             */
            Double preceedingClickEventSupport = null;
            supportDetails.put("preceedingClickEventExists", false);
            if(nearestPreceedingClickEvent != null){
                supportDetails.put("preceedingClickEventExists", true);

                //Accumulate support from baseURI
                String baseURI = nearestPreceedingClickEvent.getBaseURI().toString();
                supportDetails.put("baseUri", baseURI);
                String baseUriPath = new URL(baseURI).getPath();
                String [] basePathSplit = baseUriPath.split("/");
                String baseUriInput = Arrays.stream(basePathSplit).filter(part->!part.equals("*")).collect(StringBuilder::new, (sb,s)->sb.append(s + " "), StringBuilder::append).toString();
                supportDetails.put("baseUriInput", baseUriInput);
                preceedingClickEventSupport = getSupport(lemmatizedTerms, baseUriInput);
                supportDetails.put("baseUriSupport", preceedingClickEventSupport);

                //Accumulate support from cssClassTerms
                if (nearestPreceedingClickEvent.getSemanticArtifacts() != null && nearestPreceedingClickEvent.getSemanticArtifacts().containsKey("cssClassTerms")){
                    JsonArray cssClassTerms = nearestPreceedingClickEvent.getSemanticArtifacts().getJsonArray("cssClassTerms");
                    supportDetails.put("cssClassTerms", cssClassTerms);
                    Iterator<Object> cssClassTermIterator = cssClassTerms.iterator();
                    while (cssClassTermIterator.hasNext()){
                        String cssClassTermEntry = (String)cssClassTermIterator.next();
                        preceedingClickEventSupport += getSupport(lemmatizedTerms, cssClassTermEntry);
                    }
                    supportDetails.put("cssClassTermsSupport", preceedingClickEventSupport - supportDetails.getDouble("baseUriSupport"));

                }

                //Accumulate support from terms
                //TODO: this is kind of a mess.
                /*
                 * This one is different because each clickEntry->terms value is a single token AND we want the support to be proportional
                 * to the position in the terms list. So matching an instance at the top of the list should net more support than matching terms
                 * lower on the list.
                 */

                if(nearestPreceedingClickEvent.getSemanticArtifacts() != null && nearestPreceedingClickEvent.getSemanticArtifacts().containsKey("terms")){
                    JsonArray matchedTerms = new JsonArray(); //For bookkeeping

                    JsonArray textTerms = nearestPreceedingClickEvent.getSemanticArtifacts().getJsonArray("terms");
                    LinkedHashMap<String, Double> supportMap = new LinkedHashMap<>();
                    lemmatizedTerms.forEach(t->supportMap.put(t, 0.0));

                    for(String lemmatizedTerm: lemmatizedTerms){

                        ListIterator<String> textTermIterator = textTerms.stream().map(o->(String)o).collect(Collectors.toList()).listIterator();

                        while (textTermIterator.hasNext()){
                            int index = textTermIterator.nextIndex();
                            String textTermEntry = textTermIterator.next();

                            CoreDocument textTermDocument = new CoreDocument(textTermEntry);
                            nlpPipeline.annotate(textTermDocument);
                            for(CoreLabel textTermToken: textTermDocument.annotation().get(CoreAnnotations.TokensAnnotation.class)){
                                if(textTermToken.get(EnglishWordAnnotator.class) && textTermToken.lemma().toLowerCase().equals(lemmatizedTerm)){
                                    double currSupport = supportMap.getOrDefault(lemmatizedTerm, 0.0);

                                    double supportToAdd = ((double) textTerms.size() - (double) index)/(double)textTerms.size();

                                    matchedTerms.add(new JsonObject()
                                            .put("inputTerm", lemmatizedTerm )
                                            .put("clickEventTerm", textTermToken.lemma().toLowerCase())
                                            .put("supportContribution", supportToAdd)
                                    );

                                    supportMap.put(lemmatizedTerm, currSupport + supportToAdd );
                                }
                            }

                        }
                    }

                    preceedingClickEventSupport += supportMap.entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue())).findFirst().get().getValue();
                    supportDetails.put("matchedClickEventTerms", matchedTerms);
                    supportDetails.put("clickEventTermsSupport", preceedingClickEventSupport - (supportDetails.getDouble("baseUriSupport") - supportDetails.getDouble("cssClassTermsSupport")));
                    supportDetails.put("preceedingClickEventSupport", preceedingClickEventSupport);

                }

            }

            log.info("pathSupport: {}", pathSupport);
            log.info("documentUrlSupport: {}", documentUrlSupport != null?documentUrlSupport:"null");
            log.info("preceedingClickEventSupport: {}", preceedingClickEventSupport != null?preceedingClickEventSupport:"null");

            double totalSupport = pathSupport;
            if(documentUrlSupport != null){
                totalSupport+=documentUrlSupport;
            }
            if(preceedingClickEventSupport != null){
                totalSupport+=preceedingClickEventSupport;
            }
            log.info("total support: {}", totalSupport);
            supportDetails.put("totalSupport", totalSupport);

            supportDetailsHistory.add(supportDetails);

            return totalSupport;

        } catch (MalformedURLException e) {
            log.error("Bad document URL! {}", originNetworkEvent.getDocumentUrl());
            throw new RuntimeException(e);
        }

    }

    private double getSupport2(List<String> ngram, String target){

        CoreDocument document = new CoreDocument(target);
        nlpPipeline.annotate(document);


        List<CoreLabel> targetLabels = document.annotation().get(CoreAnnotations.TokensAnnotation.class);
        Iterator<CoreLabel> targetIterator = targetLabels.iterator();
        Iterator<String> ngramIterator = ngram.iterator();

        int numMatches = 0;
        boolean matchedLast = false;
        while (targetIterator.hasNext()){
            CoreLabel reference = targetIterator.next();

            if(ngramIterator.hasNext()){
                String sample = ngramIterator.next();

                if(sample.equals(reference.lemma().toLowerCase())){
                    matchedLast = true;
                }else{

                    matchedLast = false;
                    ngramIterator = ngram.iterator(); //Reset the n-gram iterator after a mismatch
                }

            }else if(matchedLast){
                matchedLast = false;
                numMatches++;
                ngramIterator = ngram.iterator(); //Reset the n-gram iterator after a match.

                if(ngramIterator.next().equals(reference.lemma().toLowerCase())){
                    matchedLast = true;
                }else{
                    ngramIterator = ngram.iterator();
                }

            }
        }

        if(matchedLast){
            numMatches++;
        }

        return (double) numMatches;

    }

    private double getSupport(List<String> lemmatizedTerms, String targetRegion){
        LinkedHashMap<String, Integer> frequencyMap = new LinkedHashMap<>();
        lemmatizedTerms.forEach(term->frequencyMap.put(term, 0));

        CoreDocument document = new CoreDocument(targetRegion);
        nlpPipeline.annotate(document);

        for(String lemmatizedTerm: lemmatizedTerms){
            for(CoreLabel token: document.annotation().get(CoreAnnotations.TokensAnnotation.class)){
                if(lemmatizedTerm.equals(token.lemma().toLowerCase())){
                    int count = frequencyMap.getOrDefault(lemmatizedTerm, 0);
                    frequencyMap.put(lemmatizedTerm, ++count);
                }
            }
        }

        /* Min of all lemmatized term frequencies is the overall support. For example if terms was 'assignment_override_students', then
         * lemmatized terms would be ['assignment', 'override', 'student'], if we then find 'assignment' 10 times, 'override' 3 times, and
         * 'student' 6 times, the overall support would be 3.
         */
        int pathSupport = frequencyMap.entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue())).findFirst().get().getValue();
        return pathSupport;
    }

    private List<String> computeLemmatizedTermTokens(String term){
        term = term.replaceAll("_", " ");
        List<String> result = new ArrayList<>();
        CoreDocument document = new CoreDocument(term);
        nlpPipeline.annotate(document);
        for(CoreLabel token: document.annotation().get(CoreAnnotations.TokensAnnotation.class)){
            result.add(token.lemma());
        }

        return result;
    }

    private List<String> preprocessNgram(List<String> ngram){
        List<String> result = new ArrayList<>();

        ngram.forEach(s->{
            CoreDocument document = new CoreDocument(s);
            nlpPipeline.annotate(document);
            for(CoreLabel token: document.annotation().get(CoreAnnotations.TokensAnnotation.class)){
                result.add(token.lemma().toLowerCase());
            }
        });

        return result;

    }

    public JsonArray getSupportDetailsHistory() {
        return supportDetailsHistory;
    }
}
