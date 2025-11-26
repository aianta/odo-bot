package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Record;

public class SelectOptionNode extends XpathAndBasePathNode {


    public static SelectOptionNode fromRecord(Record record){

        SelectOptionNode result = fromRecord(record, new SelectOptionNode());

        return result;
    }

}
