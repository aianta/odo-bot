package ca.ualberta.odobot.taskplanner;

import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.Evaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class TaskPlanningEvaluatorForSingleTargets implements Evaluator {


    private static final Logger log = LoggerFactory.getLogger(TaskPlanningEvaluator.class);

    public List<Path> _paths = new ArrayList<>();

    private int lastPathLength = 0;

    //UUIDs of defined input parameters
    private Set<String> inputParameters;
    //UUIDs of related API call vertices. Essentially acting as a pool of potential target vertices.
    private Set<String> apiCalls;

    private Set<String> objectParameters;

    public TaskPlanningEvaluatorForSingleTargets(Set<String> inputParameters, Set<String> objectParameters, Set<String> apiCalls){
        this.inputParameters = inputParameters;
        this.apiCalls = apiCalls;
        this.objectParameters = objectParameters;

        log.info("InputParameter Set: {} ", inputParameters );
        log.info("SchemaParameter Set: {} ", objectParameters );
        log.info("apiCall Set: {}", apiCalls);
    }

    @Override
    public Evaluation evaluate(Path path) {

        if(path.length() != lastPathLength){
            log.info("Evaluating paths of length: {}", path.length());
            lastPathLength = path.length();

//            //_paths.clear();
            Iterator<Path> it = _paths.iterator();
            while (it.hasNext()){
                Path curr = it.next();
                if(numTargetsHit(curr, apiCalls) == 0){
                    it.remove();
                    continue;
                }

                if(curr.length() < lastPathLength &&
                        !apiCalls.contains(curr.endNode().getProperty("id"))){
                    it.remove();
                }
            }
        }


        Node endNode = path.endNode();
        String endNodeId = (String)endNode.getProperty("id");


        if(endNode.hasLabel(Label.label("DataEntryNode")) && !inputParameters.contains(endNodeId)){
            //Stop exploring paths which contain data entry nodes that do not appear in our input parameter set.
            //These would be input parameters for which we don't have values or which have not been deemed to be relevant to the task at hand.
            return Evaluation.EXCLUDE_AND_PRUNE;
        }

//        if(endNode.hasLabel(Label.label("APINode")) && !apiCalls.contains(endNodeId)){
//            //Stop exploring paths which contain API nodes that do not appear in our set of potential target API nodes.
//            return Evaluation.EXCLUDE_AND_PRUNE;
//        }
        if(endNode.hasLabel(Label.label("APINode")) && apiCalls.contains(endNodeId)){
            _paths.add(path);
            return Evaluation.INCLUDE_AND_PRUNE;
        }

        if(endNode.hasLabel(Label.label("CollapsedClickNode")) &&
                endNode.hasRelationship(Direction.OUTGOING, RelationshipType.withName("PARAM")) &&
                !objectParameters.contains(endNodeId)
        ){
            //Stop exploring paths which contain object parameters that are not referenced in the task description
            return Evaluation.EXCLUDE_AND_PRUNE;
        }

        _paths.add(path);
        //We're looking for the least number of API calls while still reaching the target API call.
        _paths.sort(Comparator.comparing(p->numAPICallsInPath((Path)p)));




        return Evaluation.INCLUDE_AND_CONTINUE;
    }

    public List<Path> getPaths(){
        Iterator<Path> it = _paths.iterator();
        int minAPICallsHit = Integer.MAX_VALUE;
        while (it.hasNext()){
            var p = it.next();
            var pTargetsHit = numAPICallsInPath(p);
            if(pTargetsHit < minAPICallsHit){
                minAPICallsHit = pTargetsHit;
            }
            if(pTargetsHit > minAPICallsHit){
                it.remove(); //Remove all paths that don't achieve the min api calls hit.
            }
        }

        //Sort the remaining paths by the number of input parameters hit
        Set<String> combinedParameters = new HashSet<>();
        combinedParameters.addAll(inputParameters);
        //combinedParameters.addAll(objectParameters);
        _paths.sort(Comparator.comparing(p->numTargetsHit((Path)p, combinedParameters)).reversed());

        return _paths;
    }

    private int numAPICallsInPath(Path p){
        int count = 0;
        Iterator<Node> it = p.nodes().iterator();
        while (it.hasNext()){
            Node curr = it.next();
            if(curr.hasLabel(Label.label("APINode"))){
                count++;
            }
        }
        return count;
    }

    private Integer numTargetsHit(Path path, Set<String> targetNodeIds){
        return computeIncludedNodes(path, targetNodeIds).size();
    }

    private Set<String> computeIncludedNodes(Path path, Set<String> targetNodeIds){

        Set<String> result = new HashSet<>();
        path.nodes().forEach(node->{
            var nodeId = (String)node.getProperty("id");
            if(targetNodeIds.contains(nodeId)){
                result.add(nodeId);
            }
        });

        return result;
    }

}
