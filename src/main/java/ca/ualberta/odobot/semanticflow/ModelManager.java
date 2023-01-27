package ca.ualberta.odobot.semanticflow;


import ca.ualberta.odobot.semanticflow.statemodel.Coordinate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModelManager {

    private static final Logger log = LoggerFactory.getLogger(ModelManager.class);

    /**
     * Like {@link #materializeXpath(String)} except stops once the {@code stop} xpath is reached.
     * Implicitly the stop path should always be shorter than the xpath, and included within the xpath.
     * @param stop The xpath at which to stop materializing.
     * @param xpath The xpath to materialize.
     * @return The materialized coordinate structure.
     */
    public static Coordinate materializeXpath(String stop, String xpath){
        if(!xpath.contains(stop)){
            log.error("Stop xpath is not contained in xpath.");
            return null;
        }
        Predicate<String> reachedStopOrRoot = remainder ->remainder.isEmpty() || remainder.equals(stop);
        return materializeXpath(xpath, reachedStopOrRoot);
    }

    public static Coordinate materializeXpath(String xpath){
        Predicate<String> reachedRoot = remainder -> remainder.isEmpty();
        return materializeXpath(xpath, reachedRoot);
    }

    /**
     * Materializes an xpath, that is, turns it into the corresponding Coordinate structure until a
     * stop condition is reached. For example the xpath has been fully consumed.
     * @param xpath the xpath to materialize.
     * @param stopCondition the condition upon which to stop materializing. {@link #materializeXpath(String)} and {@link #materializeXpath(String, String)} make use of this.
     * @return the materialized coordinate structure.
     */
    private static Coordinate materializeXpath(String xpath, Predicate<String> stopCondition){
        int lastSlash = xpath.lastIndexOf('/');
        String component = xpath.substring(lastSlash+1);
        String remainder = xpath.substring(0,lastSlash);

        Coordinate coordinate = new Coordinate();
        coordinate.xpath = xpath;
        coordinate.index = getIndex(component);
        coordinate.parent = stopCondition.test(remainder)?null:materializeXpath(remainder, stopCondition);
        if(coordinate.parent != null){
            coordinate.parent.addChild(coordinate);
        }

        return coordinate;
    }

    public static int getIndexFromXpath(String xpath){
        int lastSlash = xpath.lastIndexOf('/');
        String component = xpath.substring(lastSlash+1);
        return getIndex(component);
    }

    private static int getIndex(String xPathComponent){
        Pattern indexPattern = Pattern.compile("[0-9]+");
        Matcher matcher = indexPattern.matcher(xPathComponent);
        if(matcher.find()){
            return Integer.parseInt(matcher.group(0));
        }else{
            return 0;
        }
    }
}
