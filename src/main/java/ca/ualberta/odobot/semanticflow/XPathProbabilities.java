package ca.ualberta.odobot.semanticflow;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class XPathProbabilities {

    private static final Logger log = LoggerFactory.getLogger(XPathProbabilities.class);

    private Multimap<String, String> rawObservations = ArrayListMultimap.create();
    private Map<XpathValue, Multimap<String,String>> conditionalObservations = new HashMap<>();


    /**
     * Store observations of watched xpaths, when an event occurs. Because events emanate from an xpath themselves
     * and we check all our watched xpaths for their values every time we get an element, in effect we can observe how
     * the other xpaths vary when a given xpath has a particular value.
     *
     * @param xpath the xpath of the event target DOM element
     * @param value the innerHTML value of the DOM element at the xpath
     * @param newObservations a <String,String> map of all the other xpaths being watched and the values they had
     *                        when this event was triggered.
     * @return the XPathProbabilities object for fluent chaining.
     */
    public XPathProbabilities observeGivenThat(String xpath, String value, Map<String, String> newObservations){
        XpathValue xv = new XpathValue(xpath, value);
        Multimap<String,String> observations = conditionalObservations.getOrDefault(xv, ArrayListMultimap.create());
        newObservations.entrySet().forEach(entry-> {
            //Exclude the conditioned upon xpath. We know that it is fixed and always set to the xpath value.
            if(!entry.getKey().equals(xv.xpath())){
                observations.put(entry.getKey(), entry.getValue());
            }
        });
        conditionalObservations.put(xv, observations);
        return this;
    }

    public XPathProbabilities put(String xpath, String value){
        rawObservations.put(xpath, value);
        return this;
    }

    public Multimap getObservationsGivenThat(String xpath, String value){
        return getObservationsGivenThat(new XpathValue(xpath, value));
    }

    public Multimap  getObservationsGivenThat(XpathValue xpathValue){
        return conditionalObservations.get(xpathValue);
    }

    public Collection<String> getRawObservationValues(String xpath){
     return rawObservations.get(xpath);
    }

    public Set<XpathValue> getXpathValues(){
        Set<XpathValue> result = new HashSet<>();
        rawObservations.forEach((key,value)->{
            XpathValue xv = new XpathValue(key, value);
            result.add(xv);
        });
        return result;
    }

    public Iterable<String> watchedXpaths(){
        return rawObservations.keySet();
    }

    public JsonObject toJson(){
        JsonObject contents = new JsonObject();

        JsonObject raw = new JsonObject();
        JsonArray conditional = new JsonArray();

        //Construct raw observations
        rawObservations.keySet().forEach(key->{
            raw.put(key, rawObservations.get(key).stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
        });

        //Construct conditional observations
        conditionalObservations.keySet().forEach(xpathValue -> {
            JsonObject observation  = new JsonObject()
                    .put("xpath", xpathValue.xpath())
                    .put("value", xpathValue.value());

            Multimap<String,String> conditionalData = conditionalObservations.get(xpathValue);
            conditionalData.keySet().forEach(key->{
                observation.put(key, conditionalData.get(key).stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
            });
            conditional.add(observation);
        });

        //Assemble the two in a single object
        contents
                .put("raw", raw)
                .put("conditional", conditional);
        return contents;
    }

    public String toString(){
        return toJson().encodePrettily();
    }

    public Map<String, Map<String, Integer>> compute(String xpath, String value){
        XpathValue xv = new XpathValue(xpath, value);
        Multimap<String,String> data = conditionalObservations.get(xv);

        Map<String, Map<String,Integer>> results = new HashMap<>();

        data.keySet().forEach(key->{
            Collection<String> values = data.get(key);
            Map<String, Integer> count = new HashMap<>();

            values.forEach(v->{
                Integer total = count.getOrDefault("all", 0);
                total++;
                count.put("all", total);

                if (v.equals("null")){
                    return;
                }

                Integer c = count.getOrDefault(v, 0);
                c++;
                count.put(v, c);

            });

            results.put(key, count);
        });

        return results;
    }
}
