package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Record;

public class CheckboxNode extends XpathAndBasePathNode {

    /**
     * Keeping a checkbox id seems reasonable for many cases, but as shown in: https://stackoverflow.com/questions/8537621/possible-to-associate-label-with-checkbox-without-using-for-id
     * Implicit association could technically be used to avoid using ids, and rather than deal with the complexities of only
     * some checkboxes having ids, I'm going to just use only the xpath for them.
     */


    public static CheckboxNode fromRecord(Record record){

        CheckboxNode result = fromRecord(record, new CheckboxNode());

        return result;
    }


}
