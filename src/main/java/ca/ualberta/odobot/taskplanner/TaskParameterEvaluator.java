package ca.ualberta.odobot.taskplanner;


import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.Evaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class TaskParameterEvaluator implements Evaluator {

    private static final Logger log = LoggerFactory.getLogger(TaskParameterEvaluator.class);


    private String targetNodeID;
    private Set<String> inputParameters;
    private Set<String> resourceParameters;

    public TaskParameterEvaluator( String targetNodeID,
                                  Set<String> inputParameters, Set<String> resourceParameters
                               ) {
        this.targetNodeID = targetNodeID;
        this.resourceParameters = resourceParameters;
        this.inputParameters = inputParameters;
    }

    @Override
    public Evaluation evaluate(Path path) {
        Node endNode = path.endNode();
        String endNodeId = (String) endNode.getProperty("id");

        if(endNodeId.equals(targetNodeID)) {
            log.info("Found path ending at target node....");
            return Evaluation.INCLUDE_AND_PRUNE;
        }

        if(endNode.hasLabel(Label.label("DataEntryNode")) &&
                !inputParameters.contains(endNodeId)){
//                !inputParameters.contains((String)endNode.getSingleRelationship(RelationshipType.withName("PARAM"), Direction.OUTGOING).getEndNode().getProperty("id"))){
            //Stop exploring paths which contain data entry nodes that do not appear in our input parameter set.
            //These would be input parameters for which we don't have values or which have not been deemed to be relevant to the task at hand.
            log.info("Excluding path because end node {} is an undefined input parameter...", endNodeId);
            return Evaluation.EXCLUDE_AND_PRUNE;
        }

        if(endNode.hasLabel(Label.label("ClickNode")) &&
                endNode.hasRelationship(Direction.OUTGOING, RelationshipType.withName("PARAM")) &&
                //Compare object parameter node id to our set of object parameter nodes. NOTE: this is not the endNode, but rather the SchemaParameter node attached to the endNode.
                //objectParameters.contains((String)endNode.getSingleRelationship(RelationshipType.withName("PARAM"), Direction.OUTGOING).getEndNode().getProperty("id"))
                !resourceParameters.contains(endNodeId)
        ){
            log.info("Excluding path because end node {} is an undefined resource parameter...", endNodeId);
            //Stop exploring paths which contain object parameters that are not referenced in the task description
            return Evaluation.EXCLUDE_AND_PRUNE;
        }

        return Evaluation.EXCLUDE_AND_CONTINUE;

    }

}
