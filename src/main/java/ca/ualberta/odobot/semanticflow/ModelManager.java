package ca.ualberta.odobot.semanticflow;


import ca.ualberta.odobot.semanticflow.exceptions.UnmergableCoordinates;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ModelManager {

    private static final Logger log = LoggerFactory.getLogger(ModelManager.class);

    /**
     * Creates a new coordinate, which is the result of merging source and target coordinates.
     * @param source coordinate to merge into.
     * @param target coordinate to merge.
     * @return merged coordinate.
     */
    public static Coordinate merge(Coordinate source, Coordinate target) {

        Set<Coordinate> pool = new HashSet<>();

        Set<String> sourceXpaths = source.getXpaths();
        Set<String> targetXpaths = target.getXpaths();

        Set<String> intersectionXpaths = new HashSet<>(sourceXpaths);
        intersectionXpaths.retainAll(targetXpaths);

        for(String xpath: intersectionXpaths){
            Optional<Coordinate> sourceMergePoint = source.getByXpath(xpath);
            Optional<Coordinate> targetMergePoint = target.getByXpath(xpath);

            if(sourceMergePoint.isPresent() && targetMergePoint.isPresent()){
                Coordinate sourceMergeCoordinate = sourceMergePoint.get();
                Coordinate targetMergeCoordinate = targetMergePoint.get();
                Coordinate result = new Coordinate();
                result.xpath = xpath;
                result.index = sourceMergeCoordinate.index;
                result.parent = sourceMergeCoordinate.parent;
                result.addChildren(sourceMergeCoordinate.getChildren());
                result.addChildren(targetMergeCoordinate.getChildren());

                pool.add(result);

            }

        }

        Coordinate root = pool.iterator().next().getRoot();

        return root;

    }


    public static Coordinate materializeXpath(String xpath){
        int lastSlash = xpath.lastIndexOf('/');
        String component = xpath.substring(lastSlash+1);
        String remainder = xpath.substring(0,lastSlash);

        Coordinate coordinate = new Coordinate();
        coordinate.xpath = xpath;
        coordinate.index = getIndex(component);
        coordinate.parent = remainder.isEmpty()?null:materializeXpath(remainder);
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
