package ca.ualberta.odobot.semanticflow;

import org.checkerframework.checker.units.qual.C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModelManager {

    private static final Logger log = LoggerFactory.getLogger(ModelManager.class);


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
