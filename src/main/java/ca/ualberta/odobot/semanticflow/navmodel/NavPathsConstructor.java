package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.IntStream;


public class NavPathsConstructor {

    private static final Logger log = LoggerFactory.getLogger(NavPathsConstructor.class);
    private final GraphDatabaseService db;

    public NavPathsConstructor(GraphDB graphDB){
        this.db = graphDB.db;
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

            List<NavPath> shortestPath = new ArrayList<>();
            shortestPath.add(paths.get(0));

            return shortestPath;

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
