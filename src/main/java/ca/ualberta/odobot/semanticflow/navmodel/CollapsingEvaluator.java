package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.Evaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static ca.ualberta.odobot.semanticflow.navmodel.BaseLabel.resolveBaseLabel;

/**
 * The CollapsingEvaluator manages traversals looking for collapsable patterns.
 *
 * When a collapsable pattern is found, the traversal is terminated.
 *
 * The collapsable pattern is stored in the {@link #collapse} field, and can be retrieved using {@link #getCollapse()}.
 * It is also possible that the evaluator fails to find a collapsable pattern. Use {@link #hasCollapse()} to determine
 * if a collapsable pattern has been found.
 *
 */
public class CollapsingEvaluator implements Evaluator {

    private static final Logger log = LoggerFactory.getLogger(CollapsingEvaluator.class);


    /**
     * PathElements provide a light-weight representation of nodes in the paths being explored during the traversal.
     */
    public static class PathElement {

        /**
         *
         * @param input A list of path elements.
         * @return A human-readable string of the list of path elements.
         */
        public static String toString(List<PathElement> input){
            StringBuilder sb = new StringBuilder();
            sb.append("|");
            input.forEach(element->{
                sb.append(String.format("%12s:%4s|", element.label, element.id));
            });
            sb.append("\n");

            return sb.toString();
        }

        /**
         *
         * @param p the path to convert.
         * @return A representation of the path given as a list of {@link PathElement} objects.
         */
        public static List<PathElement> fromPath(Path p){
            List<PathElement> result = new ArrayList<>();
            p.nodes().forEach(node->result.add(fromNode(node)));
            return result;
        }


        /**
         * Utility function for converting a single neo4j node object into
         * it's {@link PathElement} representation.
         *
         * Note: the label of the path element is singular and given by {@link BaseLabel#resolveBaseLabel(Node)}
         *
         *
         * @param n the node to represent as a path element.
         * @return a path element representing the node.
         */
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

    /**
     * Pattern is an internal helper class for {@link CollapsingEvaluator}.
     *
     * It is initialized with a single path. Once initalized, a pattern allows
     * us to easily check if a new different path matches the pattern of the path
     * with which the pattern object was initialized.
     *
     * Here a path matches a pattern if the nodes it contains have the same base labels
     * in the same order as that of the pattern.
     *
     * For example:
     *
     * P1: ClickNode -> EffectNode -> ApiNode -> EffectNode
     * P2: ClickNode -> EffectNode -> ApiNode -> EffectNode
     * P3: EffectNode -> EffectNode -> ApiNode -> EffectNode
     *
     * Assume the pattern was initialized with P1. In that case P2 will match the pattern, while P3 will not.
     *
     * Note that only the base label  of the nodes and their order is checked for a match.
     *
     * A pattern object also provides a convenient place to store instances of the pattern that are found.
     *
     * A pattern is said to 'have support' if it contains at least 2 instances matching itself.
     *
     */
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
         * This method is used to retrieve instances of the pattern who share a unique last node. That is, the last node in the instances have the same {@link Node#getElementId()}.
         *
         * This end node is considered the end anchor for a collapsable pattern.
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

        /**
         * Determine if a given path matches the pattern associated with this pattern object.
         * @param path the path to check
         * @return true if the nodes of the path match all the available pattern node labels in the same order.
         *
         * NOTE: It is possible for a path that is longer than the pattern to match so long as the nodes have the same base labels as the pattern.
         * For example, say we have the following pattern:
         *
         * ClickNode -> APINode
         *
         * A path P:
         *
         * ClickNode -> APINode -> EffectNode
         *
         * Would still match the pattern because the first two nodes of the path match the pattern.
         *
         */
        public boolean matches(Path path){

            List<PathElement> _path = PathElement.fromPath(path);

            //log.debug("Testing if path: \n{}\nmatches pattern: \n{}\n ", MatrixElement.toString(_path), MatrixElement.toString(pattern));


            ListIterator<PathElement> pathIterator = _path.listIterator();
            ListIterator<PathElement> patternIterator = pattern.listIterator();

            while (patternIterator.hasNext()){

                PathElement pathNode = pathIterator.next();

                PathElement patternNode = patternIterator.next();

                if(!patternNode.label.equals(pathNode.label) || //If the labels do not match, then this is not a match.
                        (patternNode.label.equals("APINode") && pathNode.label.equals("APINode") && !patternNode.elementId.equals(pathNode.elementId)) //If the nodes have matching APINode labels, they must be an ending anchor (that is, the same elementId), otherwise, no match.

                ){
                    return false;
                }

            }

            return true;
        }

        /**
         * A convenience method of printing out the state of a pattern.
         * @return A human-readable string representing the pattern object.
         */
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

    /**
     * A list of the patterns computed for the last length of traversals.
     */
    List<Pattern> lastPatterns = new ArrayList<>();

    /**
     * A list of the patterns computed for the current length of traversals.
     */
    List<Pattern> patterns = new ArrayList<>();

    /**
     * The current length of the traversals being evaluated.
     */
    int length = -1;


    /**
     * A field to store the collapsable pattern if it is found.
     */
    private Collapse collapse = null;


    @Override
    public Evaluation evaluate(Path path) {

        /**
         * This method is called for every path being explored as part of the traversal.
         *
         * NOTE: {@link CollapsingEvaluator} assumes a breadth-first search (BFS) traversal strategy.
         *
         * Under BFS we will explore all paths of length 1, then all paths, of length 2, then all paths of length 3 and so on.
         *
         * To compute collapsable paths we need to work with all paths of a given length at once to see if they share common patterns in the order of their node's base labels.
         *
         * So this method can be thought of as having 2 sections.
         *
         * 1) This section is responsible for accumulating patterns for path of a given length, let's call this length k.
         * 2) This section is responsible for determining when we have finished exploring paths of a certain length. And can attempt to detect collapsable patterns of length k.
         *
         */

        /**
         * This is the logic for section 2). If the length of the current path is different from the length of the last path, that means we have finished processing
         * all the paths of the previous length.
         */
        if(length  != path.length()){
            length = path.length();

            if(length != -1){//Don't try to compute patterns for the singular path consisting of just the starting node of the traversal.

                //Print the patterns discovered with paths of the previous length.
                printPatterns();

                //See if there is a collapsable pattern for paths of the previous length.
                Pattern collapsePattern = patterns.stream()
                        /**
                         * If a pattern exists where multiple instances share an end node, we have a collapse.
                         */
                        .filter(pattern -> pattern.size() > 1)
                        .filter(pattern->{
                            var commonEndNodeCollection = pattern.getByCommonEndNode();
                            return commonEndNodeCollection.entrySet().stream().filter(entry->entry.getValue().size() > 1).findAny().isPresent();
                        })
                        .findAny()
                        .orElse(null)
                ;

                //If there is
                if(collapsePattern != null){

                    //Compute the end anchor for the collapsable pattern
                    var commonEndNodeCollection = collapsePattern.getByCommonEndNode();



                    log.info("Found {} collapsable patterns. ", commonEndNodeCollection.entrySet().stream()
                            .filter(e->e.getValue().size() > 1)
                            .count()
                    );

                    log.info("This is the collapsePattern:\n{}", collapsePattern.toString());

                    Map.Entry<String, List<List<PathElement>>> entry =  commonEndNodeCollection.entrySet().stream()
                            .filter(e->e.getValue().size() > 1)
                            .findAny()
                            .orElse(null);

                    List<PathElement> firstInstance = entry.getValue().get(0);

                    PathElement startingNode = firstInstance.get(0);
                    PathElement endingNode = firstInstance.get(firstInstance.size()-1);

                    //Create and set the collapse object.
                    collapse = new Collapse(startingNode, endingNode, entry.getValue());

                    printAnchoredPatterns();
                }



            }

            /**
             *  Reset the patterns, that is, clear {@link #lastPatterns}, then move the paths we discovered into {@link #lastPatterns}, then clear {@link #patterns} so
             *  we can start accumulating patterns for paths of the new length.
             */

            resetPatterns();

        }

        //log.info("Evaluating: {}", path.toString());

        //If we've found a collapse we're done.
        if(collapse != null){ //If we've found a collapse, terminate all traversals.
            log.info("Traversal terminating because collapse has been found.");
            return Evaluation.EXCLUDE_AND_PRUNE;
        }

        //Otherwise compare the current path to our list of pattern objects.
        boolean matched = false;
        for(Pattern pattern: patterns){
            if(pattern.matches(path)){ //If the path matches one of the patterns
                pattern.instances.add(PathElement.fromPath(path)); //Add it to the instances of that pattern
                matched = true;
                break;
            }
        }

        //If the current path did not match any of our existing patterns, add it as a possible new pattern to track.
        if(!matched){
            patterns.add(new Pattern(path));
        }


        //If the length of the paths we are considering is less than two, we keep expanding.
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
