package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.Evaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class CollapsingEvaluator implements Evaluator {

    private static final Logger log = LoggerFactory.getLogger(CollapsingEvaluator.class);

    List<Collapse> collapses = new ArrayList<>();

    int length = -1;
    List<String> endNodes = new ArrayList<>();
    List<String> endNodeTypes = new ArrayList<>();
    List<Path> paths = new ArrayList<>();

    List<Path> toKeep = new ArrayList<>();

    Map<String, List<Path>> anchoredPaths = new HashMap<>();




    @Override
    public Evaluation evaluate(Path path) {

        if(length != path.length()){
            processAndReset();
            length = path.length();
        }

        //Process anchoredPaths
        for(Map.Entry<String, List<Path>> entry: anchoredPaths.entrySet()){
            String anchorId = entry.getKey();
            Iterator<Node> reverseIterator =  path.reverseNodes().iterator();
            reverseIterator.next();
            Node lastNode = reverseIterator.next();
            if(((String)lastNode.getProperty("id")).equals(anchorId)){

                //TODO -> I should get some collapses here no?

                return Evaluation.EXCLUDE_AND_PRUNE;
            }
        }


        //If we've made it this far we're looking to keep the path if it appears in the to keep list
        if(toKeep(path)){
            //Collect endNode Ids
            String endNodeId = (String)path.endNode().getProperty("id");
            endNodes.add(endNodeId);

            //Collect endNode types/labels
            path.endNode().getLabels().forEach(label -> endNodeTypes.add(label.name()));

            //Collect paths
            paths.add(path);

            return Evaluation.INCLUDE_AND_CONTINUE;
        }


        return Evaluation.EXCLUDE_AND_PRUNE;
    }

    private void processAndReset(){

        //Go through each path for the current length, and count how many paths end with each observed endNode.
        Map<String, List<Path>> idMap = new HashMap<>();
        Map<String, List<Path>> typeMap = new HashMap<>();

        //Populate idMap
        endNodes.forEach(id->{

            List<Path> pathsWithThisEndNode = paths.stream().filter(path->((String)path.endNode().getProperty("id")).equals(id)).collect(Collectors.toList());

            List<Path> endNodePaths = idMap.getOrDefault(id, new ArrayList<>());
            endNodePaths.addAll(pathsWithThisEndNode);
            idMap.put(id, endNodePaths);

            log.info("{} paths have the same end node {}", pathsWithThisEndNode.size(), id);

        });

        //Paths that have been anchored should be halted.
        anchoredPaths = idMap.entrySet().stream().filter(entry->entry.getValue().size() > 1).collect(HashMap::new, (map,entry)->map.put(entry.getKey(), entry.getValue()), HashMap::putAll);


        //Populate typeMap
        endNodeTypes.forEach(type->{
            List<Path> pathsWithThisTypeOfEndNode = paths.stream().filter(path->{

                Set<String> labels = new HashSet<>();
                Iterator<Label> it = path.endNode().getLabels().iterator();
                while (it.hasNext()){
                    labels.add(it.next().name());
                }

                return labels.contains(type);
            }).collect(Collectors.toList());

            List<Path> endNodePaths = typeMap.getOrDefault(type, new ArrayList<>());
            endNodePaths.addAll(pathsWithThisTypeOfEndNode);
            typeMap.put(type, endNodePaths);

            log.info("{} paths have end node type {}", pathsWithThisTypeOfEndNode.size(), type);
        });


        //A path should be kept if it appears in a typeMap list of size 2.
        toKeep = typeMap.values().stream()
                .filter(paths->paths.size()>1)
                .collect(ArrayList::new, ArrayList::addAll, ArrayList::addAll);




        paths.clear();
        endNodes.clear();
        endNodeTypes.clear();
    }


    private boolean toKeep(Path path){

        for(Path p: toKeep){
            if(isTypeEquivalent(p, path)){
                return true;
            }
        }

        return false;
    }

    private boolean isTypeEquivalent(Path oldPath, Path newPath){

        if (oldPath.length() != newPath.length() - 1){
            log.error("Old path length is {}, expected {}. New path length is: {}", oldPath.length(), newPath.length()-1, newPath.length() );
            throw new RuntimeException("Path equivalence mismatch exception!");
        }

        Iterator<Node> newPathIt = newPath.nodes().iterator();
        Iterator<Node> oldPathIt = oldPath.nodes().iterator();

        while (oldPathIt.hasNext()){

            Node newPathNode = newPathIt.next();
            Node oldPathNode =  oldPathIt.next();

            Set<String> newPathNodeLabels = new HashSet<>();
            newPathNode.getLabels().forEach(label->newPathNodeLabels.add(label.name()));

            Set<String> oldPathNodeLabels = new HashSet<>();
            oldPathNode.getLabels().forEach(label->oldPathNodeLabels.add(label.name()));

            //If they have different numbers of labels they are not type equivalent.
            if(newPathNodeLabels.size() != oldPathNodeLabels.size()){
                return false;
            //If they have the same label they are equivalent
            }else if(newPathNodeLabels.containsAll(oldPathNodeLabels) && oldPathNodeLabels.containsAll(newPathNodeLabels)){
                continue;
            }else{
            //If they don't have the same labels they are not equivalent
                return false;
            }


        }

        //Got here only if all nodes along the old path are type equivalent with those found on the new path.
        return true;

    }

}

