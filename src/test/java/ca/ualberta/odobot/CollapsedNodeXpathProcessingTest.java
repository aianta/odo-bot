package ca.ualberta.odobot;

import ca.ualberta.odobot.semanticflow.navmodel.NavPath;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CollapsedNodeXpathProcessingTest {

    private static final Logger log = LoggerFactory.getLogger(CollapsedNodeXpathProcessingTest.class);

    private static final String [] example1 = new String []
            {"/html/body/div[3]/div[2]/div/div[2]/div[1]/div/div/div[4]/div/div[2]/div/div[17]/div/a",
            "/html/body/div[3]/div[2]/div/div[2]/div[1]/div/div/div[4]/div/div[2]/div/div[11]/div/a",
            "/html/body/div[3]/div[2]/div/div[2]/div[1]/div/div/div[4]/div/div[2]/div/div[3]/div/a",
            "/html/body/div[3]/div[2]/div/div[2]/div[1]/div/div/div[4]/div/div[2]/div/div[1]/div/a",
            "/html/body/div[3]/div[2]/div/div[2]/div[1]/div/div/div[4]/div/div[2]/div/div[4]/div/a",
            "/html/body/div[3]/div[2]/div/div[2]/div[1]/div/div/div[4]/div/div[2]/div/div[19]/div/a",
            "/html/body/div[3]/div[2]/div/div[2]/div[1]/div/div/div[4]/div/div[2]/div/div[9]/div/a",
            "/html/body/div[3]/div[2]/div/div[2]/div[1]/div/div/div[4]/div/div[2]/div/div[12]/div/a"};

    private static final String [] example2 = new String []
            {"/html/body/div[5]/span/span/div/div/div/div/div/span/form/button",
            "/html/body/div[4]/span/span/div/div/div/div/div/span/form/button",
            "/html/body/div[6]/span/span/div/div/div/div/div/span/form/button"};

    private static final String [] example3 = new String []
            {"/html/body/div[3]/div[2]/div[2]/div[3]/div[1]/div/div[5]/div/ul[1]/li[5]/div/div/div[2]/a",
                    "/html/body/div[3]/div[2]/div[2]/div[3]/div[1]/div/div[5]/div/ul[1]/li[3]/div/div/div[2]/a",
                    "/html/body/div[3]/div[2]/div[2]/div[3]/div[1]/div/div[5]/div/ul[1]/li[4]/div/div/div[2]/a",
                    "/html/body/div[3]/div[2]/div[2]/div[3]/div[1]/div/div[5]/div/ul[1]/li/div/div/div[2]/a",
                    "/html/body/div[3]/div[2]/div[2]/div[3]/div[1]/div/div[5]/div/ul[1]/li[2]/div/div/div[2]/a",
                    "/html/body/div[3]/div[2]/div[2]/div[3]/div[1]/div/div[5]/div/ul[1]/li[1]/div/div/div[2]/a"};

    private static final String [] example4 = new String []
            {"/html/body/div[3]/div[2]/div[2]/div[3]/div[1]/div/div[3]/ul/li/div/div[2]/ul/li/div[1]/div/div[3]/a",
            "/html/body/div[3]/div[2]/div[2]/div[3]/div[1]/div/div[3]/ul/li/div/div[2]/ul/li[8]/div[1]/div/div[3]/a",
            "/html/body/div[3]/div[2]/div[2]/div[3]/div[1]/div/div[3]/ul/li/div/div[2]/ul/li[6]/div[1]/div/div[3]/a",
            "/html/body/div[3]/div[2]/div[2]/div[3]/div[1]/div/div[3]/ul/li/div/div[2]/ul/li[4]/div[1]/div/div[3]/a",
            "/html/body/div[3]/div[2]/div[2]/div[3]/div[1]/div/div[3]/ul/li/div/div[2]/ul/li[3]/div[1]/div/div[3]/a",
            "/html/body/div[3]/div[2]/div[2]/div[3]/div[1]/div/div[3]/ul/li/div/div[2]/ul/li[7]/div[1]/div/div[3]/a"};


    @Test
    public void test(){
        NavPath.findCommonXpath(example1);
        NavPath.findCommonXpath(example3);
        NavPath.findCommonXpath(example2);
    }

    @Test
    public void dynamicXPath(){
        NavPath.findDynamicXPath(example1);
        NavPath.findDynamicXPath(example4);
    }
}
