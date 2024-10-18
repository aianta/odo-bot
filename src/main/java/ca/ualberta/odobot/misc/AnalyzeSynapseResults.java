package ca.ualberta.odobot.misc;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;

import static ca.ualberta.odobot.semanticflow.Utils.computeXpathNoRoot;
import static java.util.stream.Collector.Characteristics.UNORDERED;

public class AnalyzeSynapseResults {

    private static final Pattern BACKEND_NODE_PATTERN = Pattern.compile("(?<=\\[)[0-9]+(?=\\])");
    private static final Logger log = LoggerFactory.getLogger(AnalyzeSynapseResults.class);

    public static void main(String args []) throws IOException {

        int predictedElementDoesNotExist = 0;
        int totalActions = 0;
        List<Integer> distances = new ArrayList<>();
        List<String> malformedActions = new ArrayList<>();


        String resultDir = "test_task_results";
        JsonArray testData = loadTestData("test_task");

        for(int i = 0; i < testData.size(); i++){
            JsonObject sample = (JsonObject) testData.getJsonObject(i);

            //Load results
            JsonArray results = new JsonArray(new String(Files.readAllBytes(Path.of(resultDir,i+".json"))));

            results = results.stream().filter(object->{
                // We're looking to filter out all result entries that don't map to an action id.
                if(object instanceof JsonObject && ((JsonObject)object).containsKey("pred_act")){
                    return true;
                }
                if(object instanceof String && ((String)object).equals("The ground truth element is not in cleaned html")){
                    return true;
                }
                return false;
            }).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);


            JsonArray referenceActions = sample.getJsonArray("actions");
            String annotationId = sample.getString("annotation_id");


            //Should map 1-to-1
            assert results.size() == referenceActions.size();
            totalActions += referenceActions.size();

            int index = -1;
            while (index < referenceActions.size()-1){
                index++;
                //Skip results that are just the string 'The ground truth element is not in cleaned html'
                if(results.getValue(index) instanceof String){
                    continue;
                }

                JsonObject result = results.getJsonObject(index);
                JsonObject action = referenceActions.getJsonObject(index);

                Integer predicted = parseBackendNodeValue(result.getString("pred_act"));

                if(predicted == null){
                    malformedActions.add(result.getString("pred_act"));
                    continue;
                }

                int target = parseBackendNodeValue(result.getString("target_act"));

                //If the agent got the element wrong, let's dive in.
                if(predicted != target){

                    action.put("raw_html", action.getString("raw_html").replaceAll("iframe", "div"));//Replace all iframes with divs, certain target elements within iframes will not resolve otherwise.

                    Document document = Jsoup.parse(action.getString("raw_html"));

                    log.info("Predicted BackendNode: {}", predicted);
                    log.info("Target BackendNode: {}", target);


                    Element predictedElement = findElementByBackendNode(document, predicted);

                    if(predictedElement == null){
                        log.info("Predicted element does not exist!");
                        predictedElementDoesNotExist++;
                        continue;
                    }

                    Element targetElement = findElementByBackendNode(document, target);

                    String predictedXpath = computeXpathNoRoot(predictedElement);
                    String targetXpath = computeXpathNoRoot(targetElement);

                    Integer treeDistance = dijkstra(document, predictedElement, targetElement);
                    distances.add(treeDistance);

                    log.info("Annotation ID: {} Action ID: {}", annotationId, action.getString("action_uid"));
                    log.info("Predicted Element:\n{}", predictedElement.outerHtml());
                    log.info("Target Element:\n{}", targetElement.outerHtml());
                    log.info("Predicted Xpath: {}", predictedXpath);
                    log.info("Target Xpath: {}", targetXpath);
                    log.info("Tree Distance: {}", treeDistance);


                }


            }



        }

        JsonObject stats = new JsonObject()
                .put("# of predicted elements that do not exist in observation",  predictedElementDoesNotExist )
                .put("# of predicted elements that do not exist in observation %", (double)predictedElementDoesNotExist/(double)totalActions )
                .put("# of malformed actions", malformedActions.size())
                .put("# of malformed actions %", (double)malformedActions.size()/(double)totalActions)
                .put("# of actions across all samples", totalActions)
                .put("Mean distance between predicted element and target element", distances.stream().mapToInt(o->o).average().getAsDouble())
                .put("Std. dev of distances between predicted element and target element", Math.sqrt(distances.stream().mapToDouble(o->(double) o).boxed().collect(VARIANCE_COLLECTOR)))
                .put("# of test samples", testData.size());


//        log.info("# of predicted elements that do not exist in observation: {} ({})", predictedElementDoesNotExist,(double)predictedElementDoesNotExist/(double)totalActions );
//        log.info("# of malformed actions: {} ({})", malformedActions.size(), (double)malformedActions.size()/(double)totalActions);
//        log.info("# of actions across all samples: {}", totalActions);
//        log.info("Mean distance between predicted element and target element: {}", distances.stream().mapToInt(o->o).average().getAsDouble());
//        log.info("Std. dev of distances between predicted element and target element: {}", Math.sqrt(distances.stream().mapToDouble(o->(double) o).boxed().collect(VARIANCE_COLLECTOR)));
//        log.info("# of test samples: {}", testData.size());

        log.info("Stats:\n{}", stats.encodePrettily());



    }

    private static Element findElementByBackendNode(Document document, int backendNode){

        Elements results = document.selectXpath("//*[@backend_node_id='%s']".formatted(Integer.toString(backendNode)));

        if(results.size() == 0){
            log.error("Could not find element with backend node: {}", backendNode);
            return null;
        }

        return results.get(0);
    }

    private static Integer parseBackendNodeValue(String s){

        Matcher matcher = BACKEND_NODE_PATTERN.matcher(s);
        boolean matchFound = matcher.find();
        if(matchFound){
            return Integer.parseInt(matcher.group(0));
        }

        log.info("Could not extract backend node value from: '{}'", s);
        return null;

    }

    private static JsonArray loadTestData(String testDataPath) throws IOException {
        JsonArray result = new JsonArray();

        File dir = new File(testDataPath);
        File[] directoryListing = dir.listFiles();
        if(directoryListing != null){
            for(File child: directoryListing){

                if(child.toPath().toString().endsWith(".json")){
                    log.info("Loading {}", child.toPath().toString());
                    JsonArray fileData = new JsonArray(new String(Files.readAllBytes(child.toPath())));
                    result.addAll(fileData);
                }

            }
        }

        log.info("Loaded {} samples", result.size() );
        return result;
    }

    /**
     * https://en.wikipedia.org/wiki/Dijkstra's_algorithm
     * @param document
     * @param src
     * @param tgt
     */
    public static Integer dijkstra(Document document, Element src, Element tgt){

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
     * https://stackoverflow.com/questions/36263352/java-streams-standard-deviation
     */
    private static final Collector<Double, double[], Double> VARIANCE_COLLECTOR = Collector.of( // See https://en.wikipedia.org/wiki/Algorithms_for_calculating_variance
            () -> new double[3], // {count, mean, M2}
            (acu, d) -> { // See chapter about Welford's online algorithm and https://math.stackexchange.com/questions/198336/how-to-calculate-standard-deviation-with-streaming-inputs
                acu[0]++; // Count
                double delta = d - acu[1];
                acu[1] += delta / acu[0]; // Mean
                acu[2] += delta * (d - acu[1]); // M2
            },
            (acuA, acuB) -> { // See chapter about "Parallel algorithm" : only called if stream is parallel ...
                double delta = acuB[1] - acuA[1];
                double count = acuA[0] + acuB[0];
                acuA[2] = acuA[2] + acuB[2] + delta * delta * acuA[0] * acuB[0] / count; // M2
                acuA[1] += delta * acuB[0] / count;  // Mean
                acuA[0] = count; // Count
                return acuA;
            },
            acu -> acu[2] / (acu[0] - 1.0), // Var = M2 / (count - 1)
            UNORDERED);

}
