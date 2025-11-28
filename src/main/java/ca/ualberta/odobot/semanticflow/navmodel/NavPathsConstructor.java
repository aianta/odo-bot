package ca.ualberta.odobot.semanticflow.navmodel;

import ca.ualberta.odobot.guidance.execution.ExecutionParameter;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.sqlite.SqliteService;
import ca.ualberta.odobot.taskplanner.TaskPlanningEvaluator;
import ca.ualberta.odobot.taskplanner.TaskPlanningEvaluatorForSingleTargets;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;


public class NavPathsConstructor {

    private static final Logger log = LoggerFactory.getLogger(NavPathsConstructor.class);
    //private final GraphDatabaseService db;

    /**
     * Map of [NodeId][ParameterNodeId]
     */
    public Map<String, String> globalParameterMap;

    private SqliteService sqliteService;

    /**
     * A predicate class which returns true if all the nodes in a path matching a given label and having an outgoing PARAM relationship, appear in a set of acceptable node ids.
     */
    private class DoesNotIncludeOtherParameters implements Predicate<Path> {

        private Set<String> parameters;
        private String nodeLabel;

        public DoesNotIncludeOtherParameters(Set<String> parameters, String nodeLabel) {
            this.parameters = parameters;
            this.nodeLabel = nodeLabel;

            log.info("{} nodes must be one of: {}", this.nodeLabel, this.parameters);
        }

        @Override
        public boolean test(Path path) {

            for (Node node : path.nodes()) {
                if (node.hasLabel(Label.label(nodeLabel)) &&
                        node.hasRelationship(Direction.OUTGOING, RelationshipType.withName("PARAM")) &&
                        !parameters.contains((String) node.getProperty("id"))) {
                    log.info("Path contained: [{}] {} ", this.nodeLabel, node.getProperty("id").toString());
                    return false;
                }
            }

            return true;
        }
    }

    public NavPathsConstructor(GraphDB graphDB, SqliteService sqliteService){
        //this.db = graphDB.db;
        this.sqliteService = sqliteService;

        //Populate the global parameter map.
        this.globalParameterMap = LogPreprocessor.neo4j.getGlobalParameterMap();
        log.info("Loaded {} parameter records into global parameter map", globalParameterMap.size());

    }

    private Node fetchNodeById(Transaction tx, String id){
        try(
            Result result = tx.execute("match (n) where n.id = '%s' return n limit 1;".formatted(id));
            ResourceIterator<Node> it = result.columnAs("n");
        ){
            if(!it.hasNext()){
                throw new NotFoundException("node with id %s could not be found!".formatted(id));
            }

            return it.next();
        }
    }

    /**
     * Like {@link #construct(Transaction, UUID, UUID)} but takes into consideration execution parameters.
     * @param tx
     * @param src
     * @param tgt
     * @param parameters
     * @return
     */
    public List<NavPath> construct(Transaction tx, UUID src, UUID tgt, List<ExecutionParameter> parameters){
        List<NavPath> paths = construct(tx, src, tgt);

        //Compute each paths' list of parameters.
        paths.forEach(path->path.computeParameters(this.globalParameterMap));

        Set<String> expectedParameters = parameters.stream()
                .map(ExecutionParameter::getNodeId)
                .map(UUID::toString)
                .collect(Collectors.toSet());

        //Compute a set of all other parameters by getting the set of all parameters in the model, and then removing the expected parameters defined in the task request.
        Set<String> otherParameters = globalParameterMap.values().stream().collect(Collectors.toSet());
        otherParameters.removeAll(expectedParameters);


        //Prune all paths which include other parameters besides those that are expected.
        paths = paths.stream()
                .filter(path->{
                    for(String p: otherParameters){
                        if(path.getParameters().contains(p)){
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());
        ;

        //Determine which paths satisfy the maximum number of parameters and return those.
        int maxSatisfiedParameters = paths.stream().map(NavPath::getParameters).mapToInt(Set::size).max().getAsInt();
        paths = paths.stream().filter(p->p.getParameters().size() == maxSatisfiedParameters).collect(Collectors.toList());

        return paths;
    }

    public List<NavPath> construct(Transaction tx, UUID src, UUID tgt){

        Node srcNode = fetchNodeById(tx, src.toString());
        Node tgtNode = fetchNodeById(tx, tgt.toString());

        NavPathsEvaluator evaluator = new NavPathsEvaluator(tgtNode);

        TraversalDescription traversal = tx.traversalDescription()
                .breadthFirst()
                .uniqueness(Uniqueness.NODE_PATH)
                .relationships(RelationshipType.withName("NEXT"), Direction.OUTGOING)
                .evaluator(evaluator);

        Traverser traverser = traversal.traverse(srcNode);

        Iterator<Path> it = traverser.iterator();


        List<NavPath> paths = new ArrayList<>();

        while (it.hasNext()){
          it.next();
        }

        it = evaluator._paths.iterator();
        while (it.hasNext()){
            NavPath navPath = new NavPath();
            navPath.setPath(it.next());
            paths.add(navPath);


        }

        paths.sort(Comparator.comparingInt(navPath -> navPath.getPath().length()));

        NavPath.printNavPaths(paths, 3);

//        List<NavPath> shortestPath = new ArrayList<>();
//        shortestPath.add(paths.get(0));
//
//        return shortestPath;
        return paths;
    }


    private int numAPICallsInPath(Path p){
        int count = 0;
        for (Node curr : p.nodes()) {
            if (curr.hasLabel(Label.label("APINode"))) {
                count++;
            }
        }
        return count;
    }

    public List<NavPath> constructV2(Transaction tx, String startingNodeId, Set<String> objectParameters, Set<String> inputParameters, Set<String> apiCalls){

        //Assume single target API Call.
        String targetNodeId = apiCalls.iterator().next();

        //Setup path candidate predicates
        Predicate<Path> onlySpecifiedInputParameters = new DoesNotIncludeOtherParameters(inputParameters, "DataEntryNode");
        Predicate<Path> onlySpecifiedObjectParameters = new DoesNotIncludeOtherParameters(objectParameters, "CollapsedClickNode");


        String findPathsQueryString = "MATCH p=(n)-[:NEXT*1..%s]->(m) WHERE n.id = \"%s\" AND m.id = \"%s\" return p;".formatted("2000", startingNodeId, targetNodeId);

        log.info("{}", findPathsQueryString);

        Instant start = Instant.now();
        try(
                Result candidatePaths = tx.execute(findPathsQueryString);
                ResourceIterator<Path> it = candidatePaths.columnAs("p");){

            Instant end = Instant.now();
            log.info("Paths query took {}ms",  Duration.between(start, end).toMillis());

            List<Path> _paths = it.stream()
                    .filter(onlySpecifiedInputParameters)
                    .filter(onlySpecifiedObjectParameters)
                    .sorted(Comparator.comparing(this::numAPICallsInPath))
                    .toList();

            //_paths.sort(Comparator.comparing(this::numAPICallsInPath));

            log.info("Found {} paths!", _paths.size());

            //Convert to nav paths and return.
            return _paths.stream().map(p->{
                NavPath navPath = new NavPath();
                navPath.setPath(p);
                return navPath;
            })
            .limit(5) //Let's chill out a bit.
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        }

    }

    /**
     * For use in task query construction
     * @param tx
     * @param startingNodeId
     * @param inputParameters
     * @param objectParameters
     * @param apiCalls
     * @return
     */
    public List<NavPath> construct(Transaction tx, String startingNodeId, Set<String> objectParameters, Set<String> inputParameters, Set<String> apiCalls ){

        Node srcNode = fetchNodeById(tx, startingNodeId);

        log.info("Path construction starting from node: {}", startingNodeId);

        //Multi-target Evaluator
        //Evaluator evaluator = new TaskPlanningEvaluator(inputParameters, objectParameters, apiCalls);
        //Single Target Evaluator.
        Evaluator evaluator =  new TaskPlanningEvaluatorForSingleTargets(inputParameters, objectParameters, apiCalls);

//        if(apiCalls.size() == 1){
//            //Single target evaluator
//            evaluator = new TaskPlanningEvaluatorForSingleTargets(inputParameters, objectParameters, apiCalls);
//        }



        TraversalDescription traversal = tx.traversalDescription()
                .breadthFirst()
                .uniqueness(Uniqueness.NODE_PATH)
                .relationships(RelationshipType.withName("NEXT"), Direction.OUTGOING)
                .evaluator(evaluator);

        Traverser traverser = traversal.traverse(srcNode);

        Iterator<Path> it = traverser.iterator();
        while (it.hasNext()){
            it.next();
        }

        List<NavPath> paths = new ArrayList<>();
        if(apiCalls.size() == 1){
            it = ((TaskPlanningEvaluatorForSingleTargets)evaluator).getPaths().iterator();
        }else{
            it = ((TaskPlanningEvaluator)evaluator).getPaths().iterator();
        }

        while (it.hasNext()){
            NavPath navPath = new NavPath();
            navPath.setPath(it.next());
            paths.add(navPath);
        }
        return paths;

    }

    public List<NavPath> constructMind2Web(Transaction tx, UUID src, UUID tgt){

        Node srcNode = fetchNodeById(tx, src.toString());
        Node tgtNode = fetchNodeById(tx, tgt.toString());

        NavPathsEvaluator evaluator = new NavPathsEvaluator(tgtNode);

        TraversalDescription traversal = tx.traversalDescription()
                .breadthFirst()
                .uniqueness(Uniqueness.NODE_PATH)
                .relationships(RelationshipType.withName("NEXT"), Direction.OUTGOING)
                .evaluator(evaluator);

        Traverser traverser = traversal.traverse(srcNode);

        Iterator<Path> it = traverser.iterator();


        List<NavPath> paths = new ArrayList<>();

        while (it.hasNext()){
            it.next();
        }

        it = evaluator._paths.iterator();
        while (it.hasNext()){
            NavPath navPath = new NavPath();
            navPath.setPath(it.next());
            paths.add(navPath);
        }

        return paths;

        // Sorts paths by length and returns shortest one.
//        paths.sort(Comparator.comparingInt(navPath -> navPath.getPath().length()));
//
//        NavPath.printNavPaths(paths, 3);
//
//        List<NavPath> shortestPath = new ArrayList<>();
//        shortestPath.add(paths.get(0));
//
//        return shortestPath;

    }




}
