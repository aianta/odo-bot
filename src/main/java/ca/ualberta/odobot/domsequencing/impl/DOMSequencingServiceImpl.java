package ca.ualberta.odobot.domsequencing.impl;

import ca.ualberta.odobot.domsequencing.*;
import ca.ualberta.odobot.semanticflow.extraction.terms.annotators.EnglishWordAnnotator;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.print.Doc;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DOMSequencingServiceImpl implements DOMSequencingService {

    private static final Logger log = LoggerFactory.getLogger(DOMSequencingServiceImpl.class);

    List<DOMSequence> database = new ArrayList<>();

    Map<DOMSegment, Integer> encodingTable = new HashMap<>();
    Map<Integer, DOMSegment> decodingTable = new HashMap<>();
    Set<DOMSequence> motifs = new HashSet<>();

    CSSManifest globalManifest = new CSSManifest();

    DirectlyFollowsManifest globalDirectlyFollowsManifest = new DirectlyFollowsManifest();

    Map<String, Integer> entityMap = new HashMap<>();
    Map<String,Integer> actionMap = new HashMap<>();

    StanfordCoreNLP pipeline = null;

    public DOMSequencingServiceImpl(){
        Properties properties = new Properties();
        properties.setProperty("englishWords.wordnetHome", "C:\\Program Files (x86)\\WordNet\\2.1");
        properties.setProperty("customAnnotatorClass.englishWords", "ca.ualberta.odobot.semanticflow.extraction.terms.annotators.EnglishWordAnnotator");
        properties.setProperty("annotators", "tokenize,ssplit,pos,lemma,englishWords");
        properties.setProperty("tokenize.options", "splitHyphenated=true");

        pipeline = new StanfordCoreNLP(properties);
    }


    public Future<List<String>> getTexts(String html){

        Pattern numbers = Pattern.compile("[0-9]+");
        Pattern pattern = Pattern.compile("(?<=[>])([a-zA-Z0-9?!;.,\\\"\\\"\\(\\)\\s]+)(?=[<])");
        Matcher matcher = pattern.matcher(html);

        List<String> results = new ArrayList<>();
        log.info("Looking for texts with regex!");
        while(matcher.find()){

            String content = matcher.group(0);
            content = content.trim();
            if(content.isEmpty() | content.isBlank() | content.length() == 1 | numbers.matcher(content).matches()){
                continue; //Don't add blank/empty strings, or strings of length 1.
            }
            results.add(content);

        }

        return Future.succeededFuture(results);

    }

    @Override
    public Future<JsonObject> process(String html) {

        Document doc = Jsoup.parse(html);


        doNLP(doc);



        DOMVisitor visitor = new DOMVisitor();
        doc.traverse(visitor);

        DOMSequence sequence = visitor.getSequence();
        CSSManifest manifest = visitor.getCssManifest();
        DirectlyFollowsManifest directlyFollowsManifest = visitor.getDirectlyFollowsManifest();

        globalDirectlyFollowsManifest.merge(directlyFollowsManifest);
        globalManifest.merge(manifest);

        database.add(sequence);

        return Future.succeededFuture(sequence.toJson());
    }

    public void doMoreNLP(){
        globalManifest.forEach((classString,cssClass)->{

            String input = classString.replaceAll("-", " ");
            input = input.replace("_", " ");

            CoreDocument document = new CoreDocument(classString);
            pipeline.annotate(document);

            for(CoreLabel tok: document.annotation().get(CoreAnnotations.TokensAnnotation.class)){
                if(tok.get(EnglishWordAnnotator.class)){
                    //The token is an english word
                    if(EnglishWordAnnotator.NOUN.contains(tok.tag())){
                        int count = entityMap.getOrDefault(tok.lemma().toLowerCase(), 0);
                        entityMap.put(tok.lemma().toLowerCase(), ++count);
                    }
                    if(EnglishWordAnnotator.VERB.contains(tok.tag())){
                        int count = actionMap.getOrDefault(tok.lemma().toLowerCase(), 0);
                        actionMap.put(tok.lemma().toLowerCase(), ++count);
                    }

                }
            }
        });
    }

    public void doNLP (Document htmlRoot){
        String nlpInput = htmlRoot.body().text();

        //Process the body
        CoreDocument document = new CoreDocument(nlpInput);
        pipeline.annotate(document);

        for(CoreLabel tok: document.annotation().get(CoreAnnotations.TokensAnnotation.class)){
            if(tok.get(EnglishWordAnnotator.class)){
                //The token is an english word
                if(EnglishWordAnnotator.NOUN.contains(tok.tag())){
                    int count = entityMap.getOrDefault(tok.lemma().toLowerCase(), 0);
                    entityMap.put(tok.lemma().toLowerCase(), ++count);
                }
                if(EnglishWordAnnotator.VERB.contains(tok.tag())){
                    int count = actionMap.getOrDefault(tok.lemma().toLowerCase(), 0);
                    actionMap.put(tok.lemma().toLowerCase(), ++count);
                }

            }
        }



    }

    public Future<String> getEntitiesAndActions(){

        doMoreNLP();


        //https://stackoverflow.com/questions/109383/sort-a-mapkey-value-by-values
        Map<String,Integer> sortedEntities = entityMap.entrySet().stream()
                .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));

        Map<String,Integer> sortedActions = actionMap.entrySet().stream()
                .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));

        StringBuilder sb = new StringBuilder();
        sortedEntities.forEach((entity, frequency)->{
            sb.append(Integer.toString(frequency) + "\t->\t" + entity + "\n");
        });

        sortedActions.forEach((action, frequency)->{
            sb.append(Integer.toString(frequency) + "\t->\t" + action + "\n");
        });

        return Future.succeededFuture(sb.toString());

    }

    public Future<String> getGlobalManifest(){
        return Future.succeededFuture(globalManifest.toString());
    }
    public Future<String> cssQuery(Set<String> query){

        if(query.size() == 0){
            return Future.failedFuture("Query is empty");
        }

        String targetClassName = query.iterator().next();
        CSSClass targetClass = globalManifest.get(targetClassName);



        return Future.succeededFuture(targetClass.toString());
    }


    public Future<String> getDirectlyFollowsManifest(){
        return Future.succeededFuture(globalDirectlyFollowsManifest.toString());
    }



    public CSSClassTree makeTree(Element element){

     return null;
    }

    public Future<String> decodeSequences(String encodedSequences){
        log.info("Got {}", encodedSequences);
        String[] encodedSequenceArray = encodedSequences.split("\n");
        String result = Arrays.stream(encodedSequenceArray).map(encodedSequence->{
            log.info("pre-pdb split: {}", encodedSequence);
            String[] data = encodedSequence.split("\\|PDB\\| = ");
            String sequenceData = data[0];
            log.info("data[1] = {}", data[1]);
            Integer frequencyData = Integer.parseInt(data[1].trim());
            DOMSequence decoded = decodeSequence(sequenceData);
            return frequencyData.toString() + " //>" + decoded.toString() + "\n";
        }).collect(StringBuilder::new, StringBuilder::append, StringBuilder::append).toString();

        return Future.succeededFuture(result);
    }

    public DOMSequence decodeSequence(String encoded){
        //808 911 908 668 565 668 612 565 560 21 292 709 795 674 572 560 560  |PDB| = 504

        String [] elements = encoded.split(" ");
        DOMSequence decodedSequence = Arrays.stream(elements)
                .map(s->Integer.parseInt(s))
                .map(numeric->decodingTable.get(numeric))
                .collect(DOMSequence::new, DOMSequence::add, DOMSequence::addAll);

        return decodedSequence;

    }

    public Future<String> getEncodedSequences(){
        encodingTable.clear();
        decodingTable.clear();
        Set<DOMSegment> alphabet = new HashSet<>();
        database.forEach(sequence->sequence.forEach(segment->alphabet.add(segment)));

        log.info("{} segments in the alphabet.", alphabet.size());

        ListIterator<DOMSegment> it = alphabet.stream().toList().listIterator();



        log.info("Creating encoding table");
        while (it.hasNext()){
            int index = it.nextIndex();
            DOMSegment segment = it.next();
            encodingTable.put(segment, index);
            decodingTable.put(index, segment);

        }


        String encoded = database.stream()
                .map(sequence->{
                    StringBuilder sb = new StringBuilder();
                    Iterator<DOMSegment> cursor = sequence.iterator();
                    while (cursor.hasNext()){
                        DOMSegment curr = cursor.next();
                        sb.append(encodingTable.get(curr));
                        if(cursor.hasNext()){
                            sb.append(" ");
                        }
                    }
                    sb.append("\n");
                    return sb.toString();
                }).collect(StringBuilder::new, StringBuilder::append, StringBuilder::append).toString();

        return Future.succeededFuture(encoded);
    }

    public Future<List<JsonObject>> getSequences(){



        List<JsonObject> result = database.stream()
                .map(sequence->sequence.toJson())
                .collect(Collectors.toList());

        return Future.succeededFuture(result);
    }

    public Future<Void> clearSequences(){
        database.clear();
        return Future.succeededFuture();
    }

    public Future<Void> testPatternExtraction(List<JsonObject> data){
        database = data.stream().map(jsonSequence->DOMSequence.fromJson(jsonSequence))
                        .collect(Collectors.toList());


        try{
            if(database.size() > 0){
                DOMSequence query = database.get(0);
                extractPatterns(query, database, 2);

                log.info("Got {} motifs...", motifs.size());
            }
        }catch (Exception e){
            log.error(e.getMessage(), e);
            return Future.failedFuture(e);
        }


        return Future.succeededFuture();
    }

    public void extractPatterns(DOMSequence query, List<DOMSequence> database, int k){
        LinkedHashMap<Integer, List> kLetterMatches = new LinkedHashMap<>();

        Map<String, Integer> wordMatches = new TreeMap<>();
        for (; k < query.size(); k++){
                log.info("{}/{}", k, query.size());
                extractQueryWords(query, database, k, wordMatches);
            }
        wordMatches = sortByValue(wordMatches);
        wordMatches.forEach((word, matches)->{
            if(matches > 4){
                log.info("{} | {}", matches, word);
            }
        });
    }

    public static <K, V extends Comparable<? super V>> Map<K, V> sortByValue(Map<K, V> map) {
        List<Map.Entry<K, V>> list = new ArrayList<>(map.entrySet());
        list.sort(Map.Entry.comparingByValue());

        Map<K, V> result = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : list) {
            result.put(entry.getKey(), entry.getValue());
        }

        return result;
    }

    private void extractQueryWords(DOMSequence query, List<DOMSequence> database, int k ,Map<String, Integer> wordMatches){
        for (int i = 0; i <= query.size()-k; i++){
            DOMSequence queryWord = new DOMSequence();

            for(int j = 0; j < k; j++){
                queryWord.add(query.get(i+j));
            }

            log.info("@queryWord: {}", queryWord.toString());

            LinkedHashMap<Integer, List<Integer>> matches = findDatabaseMatches(database, k, queryWord);

            wordMatches.put(queryWord.toString(), totalMatches(matches));



//            int finalI = i;
//            matches.forEach((databaseIndex, matchingIndices)->{
//                log.info("Found {} matches in database entry {} @ {}", matchingIndices.size(), databaseIndex, matchingIndices);
//                for( int n = 0; n < matchingIndices.size(); n++){
//
//                        //expandMatch(query, database.get(databaseIndex), finalI, matchingIndices.get(n), k);
//                }
//            });


        }
    }

    private int totalMatches(LinkedHashMap<Integer, List<Integer>> matches){
        int matchCount = 0;
//        matches.forEach((databaseIndex, matchingIndices)->{
//            //log.info("in database sequence [{}] found {} matches", databaseIndex, matchingIndices.size());
//
//        });

        Iterator<List<Integer>> it = matches.values().iterator();
        while (it.hasNext()){
            List<Integer> curr = it.next();
            matchCount += curr.size();
        }

        return matchCount;
    }

    private LinkedHashMap<Integer,List<Integer>> findDatabaseMatches(List<DOMSequence> database, int k, DOMSequence queryWord){
        LinkedHashMap<Integer,List<Integer>> exactMatches = new LinkedHashMap<>();

        for(int dbIndex = 0; dbIndex < database.size(); dbIndex++){
            DOMSequence databaseSequence = database.get(dbIndex);

            for(int i = 0; i <= databaseSequence.size() - k; i++){
                DOMSequence kLetterWord = new DOMSequence();

                for (int j = 0; j < k; j++){
                    kLetterWord.add(databaseSequence.get(i+j));
                }

                var wordsMatch = true;

                for (int m = 0; m < kLetterWord.size(); m++){
                    if(!kLetterWord.get(m).equals(queryWord.get(m))){
                        wordsMatch = false;
                    }
                }

                if(wordsMatch){
                    var listOfMatchingIndices = exactMatches.getOrDefault(dbIndex, new ArrayList<Integer>());
                    listOfMatchingIndices.add(i);
                    exactMatches.put(dbIndex, listOfMatchingIndices);
                }
            }
        }

        return exactMatches;
    }

    private void expandMatch(DOMSequence querySequence, DOMSequence databaseSequence, int querySequenceIndex, int databaseSequenceIndex, int k ){

        int wordLength = k;
        int queryBeginIndex = querySequenceIndex;
        int queryEndIndex = querySequenceIndex + wordLength;
        int databaseBeginIndex = databaseSequenceIndex;
        int databaseEndIndex = databaseSequenceIndex + wordLength;

        boolean flipExpansionDirection = true;

        log.info("Expanding match");

        while(queryBeginIndex >= 0 && databaseBeginIndex >= 0 && queryEndIndex < querySequence.size() && databaseEndIndex < querySequence.size()){
            DOMSequence queryWord = new DOMSequence(querySequence.subList(queryBeginIndex, queryEndIndex));
            DOMSequence databaseWord = new DOMSequence(databaseSequence.subList(databaseBeginIndex, databaseEndIndex));

            log.info("queryWord: {} databaseWord: {}", queryWord.toString(), databaseWord.toString());

            //TODO Smith Waterman
            smithWatermanComparison(queryWord, databaseWord, wordLength, (a,b)->{
                log.info("query word: {}\tdatabase word: {}", queryWord, databaseWord);
                log.info("*** matched sequence 1: {}", a.toString());
                log.info("*** matched sequence 2: {}", b.toString());
                motifs.add(a);
                motifs.add(b);
                log.info("done");
            });

            if(flipExpansionDirection){
                flipExpansionDirection = false;
                queryBeginIndex--;
                databaseBeginIndex--;
                wordLength = queryEndIndex - queryBeginIndex;
            }else{
                flipExpansionDirection = true;
                queryEndIndex++;
                databaseEndIndex++;
                wordLength = queryEndIndex - queryBeginIndex;
            }
        }

    }


    private void smithWatermanComparison(DOMSequence a, DOMSequence b, int k, BiConsumer<DOMSequence, DOMSequence> alignedSequenceConsumer){
        int [][] matrix = new int[k+1][k+1];

        for(int i = 0; i <= k; i++){
            for(int j = 0; j <= k; j++){
                matrix[i][j] = 0;
            }
        }


        int gapLength = 1;

        for (int i = 1; i <= k; i++){ //Rows
            for(int j = 1; j <= k; j++){ //cols



                //log.info("a[{}]={}\tb[{}]={}", i-1, a.get(i-1).tag(), j-1, b.get(j-1).tag());

                int score = calculateScore(a.get(i-1), b.get(j-1));
                int gapScore = calculateGap(gapLength); // -2
                log.info("score: {} \tgap score: {}", score, gapScore);
                //log.info ("gapScore: {} , length: {}", gapScore, gapLength);

//                if(score > 0){
//                    gapLength = 0;
//                }else{
//                    gapLength++;
//                }

//                int rowMax = 0;
//                int colMax = 0;
//                for (int m = 0; m <= i; m++){ //Rows
//                    if(matrix[m][j] > colMax){
//                        colMax = matrix[m][j];
//                    }
//                }
//                for(int n = 0; n < j; n++){
//                    if(matrix[i][n] > rowMax){
//                        rowMax = matrix[i][n];
//                    }
//                }

                log.info("matrix[{}][{}] = max({},diag:{},left:{},top:{})", i, j,
                        0,
                        matrix[i-1][j-1] + score,
                        matrix[i][j-1] + gapScore,
                        matrix[i-1][j] + gapScore);

                List<Integer> list = Arrays.asList(
                        0,
                        matrix[i-1][j-1] + score,
                        matrix[i][j-1] + gapScore,
                        matrix[i-1][j] + gapScore
                );


//                List<Integer> list = Arrays.asList(
//                        0,
//                        matrix[i-1][j-1] + score,
//                        rowMax + gapScore,
//                        colMax + gapScore
//                );

                matrix[i][j] = Collections.max(list);

            }
        }

        printMatrix(matrix);

        //highest value
        int highestValue = 0, highestI = 0, highestJ = 0;
        for(int i = 1; i <=k; i++){
            for(int j = 1; j <=k; j++){
                if(matrix[i][j] > highestValue){
                    highestValue = matrix[i][j];
                    highestI = i;
                    highestJ = j;
                }
            }
        }

        //backtracking
        int i = highestI;
        int j = highestJ;

        DOMSequence outA = new DOMSequence();
        DOMSequence outB = new DOMSequence();

        while(i != 0 && j != 0 && matrix[i][j] != 0){

            if (matrix[i-1][j-1] == matrix[i][j] - calculateScore(a.get(i-1), b.get(j-1))){
                outA.add(0, a.get(i-1));
                outB.add(0, b.get(j-1));
                i -= 1;
                j -= 1;
            }else if(matrix[i][j-1] == matrix[i][j] - calculateGap(1)){
                outA.add(0,new DOMSegment("-",""));
                outB.add(0, b.get(j-1));
                j -= 1;
            }else{
                outA.add(0, a.get(i-1));
                outB.add(0, new DOMSegment("-",""));
                i -= 1;
            }
        }

        alignedSequenceConsumer.accept(outA, outB);

//        Stack<String> actionsStack = new Stack<>();
//
//        DOMSequence alignedSequenceA = new DOMSequence();
//        DOMSequence alignedSequenceB = new DOMSequence();
//
//        while(i > 0 && j > 0){
//            int diagCell = matrix[i-1][j-1];
//            int leftCell = matrix[i][j-1];
//            int topCell = matrix[i - 1][j];
//
//
//            log.info("diagCell: {}", diagCell);
//            log.info("leftCell: {}", leftCell);
//            log.info("topCell: {}", topCell);
//            List<Integer> list = Arrays.asList(
//                    diagCell,
//                    leftCell,
//                    topCell
//            );
//
//            int maxCell = Collections.max(list);
//            log.info("maxCell: {}", maxCell);
//            if(maxCell == diagCell){
//                alignedSequenceA.add(0, a.get(i-1));
//                alignedSequenceB.add(0, b.get(j-1));
//                i = i - 1;
//                j = j - 1;
//            }else if(maxCell == leftCell){
//                alignedSequenceA.add(0, new DOMSegment("-", ""));
//                alignedSequenceB.add(0, b.get(j-1));
//                j = j - 1;
//            } else if (maxCell == topCell) {
//                alignedSequenceA.add(0, a.get(i-1));
//                alignedSequenceB.add(0, new DOMSegment("-",""));
//                i = i - 1;
//            }
//        }
//
//        alignedSequenceConsumer.accept(alignedSequenceA, alignedSequenceB);
//

//
//        int sequenceAIndex = 0, sequenceBIndex = 0;
//
//        while(!actionsStack.isEmpty()){
//            String action = actionsStack.pop();
//            log.info("processing action: {}", action);
//            switch (action){
//                case "align":
//                    alignedSequenceA.add(a.get(sequenceAIndex ++));
//                    alignedSequenceB.add(b.get(sequenceBIndex ++));
//                    break;
//                case "insert":
//                    alignedSequenceA.add(new DOMSegment("-", ""));
//                    alignedSequenceB.add(b.get(sequenceBIndex ++));
//                    break;
//                case "delete":
//                    alignedSequenceA.add(a.get(sequenceAIndex ++));
//                    alignedSequenceB.add(new DOMSegment("-", ""));
//                    break;
//            }
//        }
//
//        while (sequenceAIndex < a.size()){
//            alignedSequenceA.add(a.get(sequenceAIndex ++));
//        }
//
//        while (sequenceBIndex < b.size()){
//            alignedSequenceB.add(b.get(sequenceBIndex ++));
//        }
//
//        alignedSequenceConsumer.accept(alignedSequenceA, alignedSequenceB);
    }

    private int calculateScore(DOMSegment a, DOMSegment b){
        return a.equals(b)?1:-1;
    }

//    private int calculateGap(int length){
//        return -2 + (length - 1) * -1;
//    }

    private int calculateGap(int length){
        return 2 * length  * -1;
    }

    private void printMatrix(int [][] matrix){
        for (int i = 0; i < matrix.length; i ++){
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < matrix[i].length; j++){
                sb.append(matrix[i][j] + "\t");
            }
            log.info("{}", sb.toString());
        }
    }

}
