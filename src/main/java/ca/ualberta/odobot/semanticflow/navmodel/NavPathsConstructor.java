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

    private class DoesNotIncludeNodes implements Predicate<Path> {
        private Set<String> exclude; //Set of node ids to exclude from paths.

        public DoesNotIncludeNodes(Set<String> exclude) {
            this.exclude = exclude;
        }

        public boolean test(Path path){
            for(Node node: path.nodes()){
                if(exclude.contains((String)node.getProperty("id"))){
                    return false;
                }
            }

            return true;
        }
    }

    private class IncludesAllParameters implements Predicate<Path>{
        private Set<String> parameters;
        private String nodeLabel;

        public IncludesAllParameters(Set<String> parameters, String nodeLabel){
            this.parameters = parameters;
            this.nodeLabel = nodeLabel;


        }

        public boolean test(Path path){

            Set<String> pathParameters = new HashSet<>();

            for(Node node: path.nodes()){
                if(node.hasLabel(Label.label(nodeLabel)) && node.hasRelationship(Direction.OUTGOING, RelationshipType.withName("PARAM"))){
                    pathParameters.add((String)node.getProperty("id"));
                }
            }

            for(String p: parameters){
                if(!pathParameters.contains(p)){
                    return false;
                }
            }

            return true;

        }
    }

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
                    //log.info("Path contained: [{}] {} ", this.nodeLabel, node.getProperty("id").toString());
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

    private int numAPICallsInPath(Path p){
        int count = 0;
        for (Node curr : p.nodes()) {
            if (curr.hasLabel(Label.label("APINode"))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Constructs possible nav paths from starting node to the api call node, only including specified object and input parameter nodes and excluding any nodes whose ids are listed
     * in the exclude set.
     * @param tx a database transaction to use in computing the path.
     * @param startingNodeId the id of the node from which the path starts.
     * @param resourceParameters the object parameters that are allowed to be used along the way.
     * @param inputParameters the input parameters that are allowed to be used along the way.
     * @param apiCalls Should be a set of a single node id that is the target node for the path.
     * @param exclude set of Node ids to exclude.
     * @return A list of nav paths from the starting node to the target node (api call). 
     */
    public List<NavPath> constructV2(Transaction tx, String startingNodeId, Set<String> resourceParameters, Set<String> inputParameters, Set<String> apiCalls, Set<String> exclude){

        return constructV2(tx, startingNodeId, resourceParameters, inputParameters, apiCalls, List.of(
                new DoesNotIncludeNodes(exclude),
                new DoesNotIncludeOtherParameters(inputParameters, "DataEntryNode"),
                new DoesNotIncludeOtherParameters(resourceParameters, "ClickNode")
        ));

    }

    /**
     * Helper method that invokes the constructV2 method filtering paths to only include those which don't contain unspecified input or object parameters.
     * @param tx
     * @param startingNodeId
     * @param resourceParameters
     * @param inputParameters
     * @param apiCalls
     * @return
     */
    public List<NavPath> constructV2(Transaction tx, String startingNodeId, Set<String> resourceParameters, Set<String> inputParameters, Set<String> apiCalls){

        //Setup path candidate predicates
        Predicate<Path> onlySpecifiedInputParameters = new DoesNotIncludeOtherParameters(inputParameters, "DataEntryNode");
        Predicate<Path> onlySpecifiedResourceParameters = new DoesNotIncludeOtherParameters(resourceParameters, "ClickNode");

        return constructV2(tx, startingNodeId, resourceParameters, inputParameters, apiCalls, List.of(onlySpecifiedInputParameters, onlySpecifiedResourceParameters));

    }

    public List<NavPath> constructV2(Transaction tx, String startingNodeId, Set<String> resourceParameters, Set<String> inputParameters, Set<String> apiCalls, List<Predicate<Path>> filters){

        //Produce a composite predicate from the filters.
        Iterator<Predicate<Path>> filterIterator = filters.iterator();
        Predicate<Path> composite = null;
        while (filterIterator.hasNext()){
            if(composite == null){
                composite = filterIterator.next();
            }else{
                composite = composite.and(filterIterator.next());
            }
        }

        //Assume single target API Call.
        String targetNodeId = apiCalls.iterator().next();


        String findPathsQueryString = "MATCH p=(n)-[:NEXT*1..%s]->(m) WHERE n.id = \"%s\" AND m.id = \"%s\" return p limit %s;".formatted("2000", startingNodeId, targetNodeId, "5000");

        log.info("{}", findPathsQueryString);

        Instant start = Instant.now();
        try(
                Result candidatePaths = tx.execute(findPathsQueryString);
                ResourceIterator<Path> it = candidatePaths.columnAs("p");){

            Instant end = Instant.now();
            log.info("Paths query took {}ms",  Duration.between(start, end).toMillis());

            List<Path> _paths = it.stream()
                    .filter(composite == null? (path)->true: composite) // If composite is null, no filters were provided. Don't filter anything if no filters were provided.
                    //.filter(new IncludesAllParameters(inputParameters, "CollapsedClickNode"))
                    .sorted(Comparator.comparing(this::numAPICallsInPath))
                    .toList();

            OptionalInt minApiCallCount = _paths.stream().mapToInt(p->numTargetsHit(p, apiCalls)).min();
            OptionalInt maxResourceParams = _paths.stream().mapToInt(p->numTargetsHit(p, resourceParameters)).max();
            OptionalInt maxInputParams = _paths.stream().mapToInt(p->numTargetsHit(p, inputParameters)).max();

            if(minApiCallCount.isPresent() && maxResourceParams.isPresent() && maxInputParams.isPresent()){
                log.info("Min API Calls: {}", minApiCallCount.getAsInt());
                log.info("Max Schema Params: {}", maxResourceParams.getAsInt());
                log.info("Max Input Params: {}", maxInputParams.getAsInt());
                List<Path> bestPaths = _paths.stream()
                        //.filter(p->numTargetsHit(p, apiCalls) == minApiCallCount.getAsInt())
                        .filter(p->numTargetsHit(p, resourceParameters) == maxResourceParams.getAsInt())
                        //.filter(p->numTargetsHit(p, inputParameters) == maxInputParams.getAsInt())
                        .toList();

                log.info("Best Paths: {}", bestPaths.size());

                if(!bestPaths.isEmpty()){
                    _paths = bestPaths;
                }
            }


            //_paths.sort(Comparator.comparing(this::numAPICallsInPath));

            log.info("Found {} paths!", _paths.size());


            //Optional<Path> pathWithMaxObjectParameters = _paths.stream().filter(new IncludesAllParameters(resourceParameters, "CollapsedClickNode")).findAny();

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
