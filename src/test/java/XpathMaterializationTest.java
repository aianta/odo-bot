import ca.ualberta.odobot.semanticflow.statemodel.Coordinate;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static ca.ualberta.odobot.semanticflow.ModelManager.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class XpathMaterializationTest {

    private static final Logger log = LoggerFactory.getLogger(XpathMaterializationTest.class);

    private static final String [] xpaths = {
            "/html/body/div[3]/div[2]/div/div[2]/div[1]/div/div[1]/div/div/div/div[1]/div/span[1]/button[2]/span/i",
            "/html/body/div[3]/div[2]/div/div[2]/div[1]/div/div[1]/div/div/div/div[2]/a/i",
            "/html/body/div[6]/div[3]/div/div[1]/form/fieldset/span/span/span/span/span/span/span[6]/span/span[2]/button",
            "/html/body/div[13]/div/div/div/div[2]/button[12]",
            "/html/body/div[3]/div[2]/div[2]/div[2]/div[1]/div/form/div[3]/button[2]"
    };

    private static String stopPath = "/html/body/div[6]/div[3]/div";
    private static String testStopPathFull = "/html/body/div[6]/div[3]/div/ul/li/a";

    @Test
    void stopMaterializationTest(){
        Coordinate result = materializeXpath(stopPath, testStopPathFull);
        printChain(result);
    }

    @Test
    void materializationTest(){

        for (String xpath: xpaths){
            String [] components = xpath.split("/");
            int expectedNumberOfCoordinates = components.length-1;
            log.info("expectedNumberOfCoordinates: {}", expectedNumberOfCoordinates);

            Coordinate leaf = materializeXpath(xpath);
            printChain(leaf);

            Set<Coordinate> set = Coordinate.toSet(leaf);
            log.info("{}", set);
            assertEquals(expectedNumberOfCoordinates, set.size());
        }


    }

    void printChain(Coordinate coordinate){

     while(coordinate.parent != null){
         log.info("xpath:{} index:{} numChildren: {}",coordinate.xpath, coordinate.index, coordinate.numChildren());
            coordinate = coordinate.parent;
     }

     log.info("xpath:{} index:{} numChildren: {}",coordinate.xpath, coordinate.index, coordinate.numChildren());
    }
}
