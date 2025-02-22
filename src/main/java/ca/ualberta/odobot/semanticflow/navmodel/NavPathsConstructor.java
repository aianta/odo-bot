package ca.ualberta.odobot.semanticflow.navmodel;

import ca.ualberta.odobot.guidance.execution.ExecutionParameter;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.Utils;
import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import ca.ualberta.odobot.sqlite.SqliteService;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


public class NavPathsConstructor {

    private static final Logger log = LoggerFactory.getLogger(NavPathsConstructor.class);
    private final GraphDatabaseService db;

    /**
     * Map of [NodeId][ParameterNodeId]
     */
    public Map<String, String> globalParameterMap;

    private SqliteService sqliteService;

    public NavPathsConstructor(GraphDB graphDB, SqliteService sqliteService){
        this.db = graphDB.db;
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

        //Compute a set of all other parameters by getting the set of all parameters, and then removing the expected parameters.
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
