package ca.ualberta.odobot.semanticflow.navmodel;


import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.Evaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static ca.ualberta.odobot.semanticflow.navmodel.BaseLabel.resolveBaseLabel;


public class CollapsingEvaluator implements Evaluator {

    private static final Logger log = LoggerFactory.getLogger(CollapsingEvaluator.class);



    public static class PathElement {

        public static String toString(List<PathElement> input){
            StringBuilder sb = new StringBuilder();
            sb.append("|");
            input.forEach(element->{
                sb.append(String.format("%12s:%4s|", element.label, element.id));
            });
            sb.append("\n");

            return sb.toString();
        }

        public static List<PathElement> fromPath(Path p){
            List<PathElement> result = new ArrayList<>();
            p.nodes().forEach(node->result.add(fromNode(node)));
            return result;
        }



        public static PathElement fromNode(Node n){
            PathElement result = new PathElement();
            result.label = resolveBaseLabel(n);
            result.elementId = n.getElementId();
            String[] splits = result.elementId.split(":");
            result.id = Long.parseLong(splits[splits.length-1]);

            return result;
        }

        public long id;
        public String elementId;
        public String label;

    }

    private class Pattern{

        List<PathElement> pattern = new ArrayList<>();

        public List<List<PathElement>> instances = new ArrayList<>();

        public Pattern(Path initPath){
            pattern = PathElement.fromPath(initPath);
            instances.add(pattern);
        }

        public int size(){
            return instances.size();
        }

        /**
         *
         * @return A map of <EndNode ElementId, List of instances with that endNode>.
         */
        public Map<String, List<List<PathElement>>> getByCommonEndNode(){

            Map<String, List<List<PathElement>>> map = new HashMap<>();

            instances.forEach(instance->{
                PathElement lastElement = instance.get(instance.size()-1);
                List<List<PathElement>> endNodeCollection = map.getOrDefault(lastElement.elementId, new ArrayList<>());
                endNodeCollection.add(instance);
                map.put(lastElement.elementId, endNodeCollection);
            });


            return map;

        }

        public boolean matches(Path path){

            List<PathElement> _path = PathElement.fromPath(path);

            //log.debug("Testing if path: \n{}\nmatches pattern: \n{}\n ", MatrixElement.toString(_path), MatrixElement.toString(pattern));


            ListIterator<PathElement> pathIterator = _path.listIterator();
            ListIterator<PathElement> patternIterator = pattern.listIterator();

            while (patternIterator.hasNext()){

                PathElement pathNode = pathIterator.next();

                PathElement patternNode = patternIterator.next();

                if(!patternNode.label.equals(pathNode.label)){
                    return false;
                }

            }

            return true;
        }

        public String toString(){
            StringBuilder sb = new StringBuilder();
            sb.append("Pattern:\n|");
            pattern.forEach(element->sb.append(String.format("%-17s|", element.label)));
            sb.append("\nInstances:\n");


            for(List<PathElement> instance:instances){
                sb.append("|");
                instance.forEach(element->{
                    sb.append(String.format("%12s:%-4s|", element.label, element.id));
                });
                sb.append("\n");
            }

            return sb.toString();
        }

    }

    List<Pattern> lastPatterns = new ArrayList<>();
    List<Pattern> patterns = new ArrayList<>();

    int length = -1;


    private Collapse collapse = null;


    @Override
    public Evaluation evaluate(Path path) {
        if(length  != path.length()){
            length = path.length();

            if(length != -1){

//                buildAllPathsMatrix();
//                printAllPaths();
                printPatterns();

                Pattern collapsePattern = patterns.stream()
                        /**
                         * If a pattern exists where multiple instances share an end node, we have a collapse.
                         */
                        .filter(pattern->{
                            var commonEndNodeCollection = pattern.getByCommonEndNode();
                            return commonEndNodeCollection.entrySet().stream().filter(entry->entry.getValue().size() > 1).findAny().isPresent();
                        })
                        .findAny()
                        .orElse(null)
                ;

                if(collapsePattern != null){

                    var commonEndNodeCollection = collapsePattern.getByCommonEndNode();

                    log.info("Found {} collapsable patterns. ", commonEndNodeCollection.size());

                    Map.Entry<String, List<List<PathElement>>> entry =  commonEndNodeCollection.entrySet().iterator().next();
                    List<PathElement> firstInstance = entry.getValue().get(0);

                    PathElement startingNode = firstInstance.get(0);
                    PathElement endingNode = firstInstance.get(firstInstance.size()-1);

                    collapse = new Collapse(startingNode, endingNode, entry.getValue());
                }
                printAnchoredPatterns();



            }

            resetPatterns();

        }

        log.info("Evaluating: {}", path.toString());

        if(collapse != null){ //If we've found a collapse, terminate all traversals.
            log.info("Traversal terminating because collapse has been found.");
            return Evaluation.EXCLUDE_AND_PRUNE;
        }

        boolean matched = false;
        for(Pattern pattern: patterns){
            if(pattern.matches(path)){

                pattern.instances.add(PathElement.fromPath(path));
                matched = true;
                break;
            }
        }

        if(!matched){
            patterns.add(new Pattern(path));
        }




        if(length < 2){ //Always expand paths until at least length 2, as there won't be a meaningful lastPatterns until then.
            log.info("Traversal continuing because path length is less than 2.");
            return Evaluation.INCLUDE_AND_CONTINUE;
        }

        //If the path matches one of the last patterns continue expanding it.
        if(lastPatterns.stream().filter(pattern -> pattern.matches(path)).findAny().isPresent()){
            log.info("Traversal continuing because path matches a pattern with sufficient support.");
            return Evaluation.INCLUDE_AND_CONTINUE;
        }else{
            log.info("Traversal terminating because path does not match a pattern with sufficient support.");
            return Evaluation.EXCLUDE_AND_PRUNE;
        }


//        return length > MAX_LENGTH? Evaluation.EXCLUDE_AND_PRUNE:Evaluation.INCLUDE_AND_CONTINUE;
    }

    /**
     * Moves patterns with more than one instance to lastPatterns, and clears patterns.
     */
    private void resetPatterns(){

        lastPatterns.clear();

        patterns.stream()
                .filter(pattern -> pattern.size() > 1)
                .forEach(pattern->lastPatterns.add(pattern));

        patterns.clear();
    }

    private void printAnchoredPatterns(){
        patterns.forEach(pattern->{
            Map<String, List<List<PathElement>>> anchoredPatterns = pattern.getByCommonEndNode();

            anchoredPatterns.entrySet().forEach(entry->{
                log.info("The following paths end with: {}", entry.getKey());

                List<List<PathElement>> instances = entry.getValue();
                StringBuilder sb = new StringBuilder();
                instances.forEach(instance->sb.append(PathElement.toString(instance)));
                log.info("\n{}",sb.toString() );
            });



        });
    }

    private void printPatterns(){
        log.info("Printing patterns!");
        patterns.forEach(pattern -> log.info("\n{}", pattern.toString()));

    }

    public Collapse getCollapse(){
        return collapse;
    }

    public boolean hasCollapse(){
        return collapse != null;
    }


}
