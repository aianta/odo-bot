package ca.ualberta.odobot.semanticflow;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        newObservations.entrySet().forEach(entry->observations.put(entry.getKey(), entry.getValue()));
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

}
