package ca.ualberta.odobot.semanticflow.navmodel;

import ca.ualberta.odobot.semanticflow.navmodel.nodes.*;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.graphdb.traversal.Traverser;
import org.neo4j.graphdb.traversal.Uniqueness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class CollapsingTraversal {

    private static final Logger log = LoggerFactory.getLogger(CollapsingTraversal.class);

    private final GraphDatabaseService db;

    public CollapsingTraversal(GraphDB graphDB){
        this.db = graphDB.db;
    }


    public List<Collapse> doCollapsePass(){
        List<Collapse> collapses = new ArrayList<>();
        Collapse collapse = null;

        do{
            Set<Node> output = new HashSet<>();

            try(
                    Transaction tx  = db.beginTx();
                    Result result = tx.execute("MATCH (n)-->() with n, count(*) as degree where degree > 1 return n;");
                    ResourceIterator<Node> nodes = result.columnAs("n");
            ){

                while (nodes.hasNext()){
                    Node node = nodes.next();
                    output.add(node);
                }


                 collapse = output.stream()
                        .peek(node->log.info("Searching for collapsable patterns with traversal starting from {}", node.getElementId()))
                        .map(node->this.findCollapse(tx, node))
                        .filter(Objects::nonNull)
                        .findAny()
                        .orElse(null)
                        ;



                if(collapse == null){
                    log.info("No collapsable patterns found!");
                    break;
                }

                applyCollapse(tx, collapse);

                collapses.add(collapse);

                tx.commit(); //Commit and finish the transaction.

            }

        }while (collapse != null);


        return collapses;

    }

    private void applyCollapse(Transaction tx, Collapse collapse){

        Iterator<List<CollapsingEvaluator.PathElement>> mergeIt = collapse.mergableIterator();

        //Get the starting anchor
        Node startingAnchor = tx.getNodeByElementId(collapse.startingAnchor().elementId);
        Node endingAnchor = tx.getNodeByElementId(collapse.endingAnchor().elementId);

        List<Node> collapsedNodes = new ArrayList<>();
        Map<Node, List<Node>> collapsedNodeParameters = new HashMap<>();

        while (mergeIt.hasNext()){
            List<Node> resourceParameters = new ArrayList<>();

            List<CollapsingEvaluator.PathElement> mergeList = mergeIt.next();
            Set<Node> nodeSet = new HashSet<>();

            mergeList.forEach(pathElement -> nodeSet.add(tx.getNodeByElementId(pathElement.elementId)));

            String baseLabel = BaseLabel.resolveBaseLabel(nodeSet.iterator().next());

            log.info("Creating collapsed node for {} label", baseLabel);

            CollapsedNode collapsedNode = switch (baseLabel){
                case "ClickNode" -> new CollapsedClickNode(nodeSet);
                case "DataEntryNode" -> new CollapsedDataEntryNode(nodeSet);
                case "EffectNode" -> new CollapsedEffectNode(nodeSet);
                case "CheckboxNode" -> new CollapsedCheckboxNode(nodeSet);
                case "RadioButtonNode" -> new CollapsedRadioButtonNode(nodeSet);
                default -> throw new RuntimeException("Uncollapsable node set!");
            };

            StringBuilder sb = new StringBuilder();
            nodeSet.forEach(n->sb.append(String.format("%s", n.getElementId())));
            log.info("Collapsing [{}] into a single node {}!", sb.toString(), collapsedNode.id().toString());

            //Find any parameters associated with these nodes and save them so we can re-attach them to the collapsed node later.
            nodeSet.forEach(node->{
                var paramResult = tx.execute("MATCH (n)-[:PARAM]->(m) WHERE elementId(n) = '%s' return m".formatted(node.getElementId()));
                if(paramResult.hasNext()){
                    ResourceIterator<Node> params = paramResult.columnAs("m");
                    params.stream().forEach(resourceParameters::add);
                }
            });


            //Delete the nodes in the node set.
            nodeSet.forEach(node ->tx.execute("MATCH (n) WHERE elementId(n) = '%s' detach delete n".formatted(node.getElementId())));

            //Create the collapsed node
            Node replacement = collapsedNode.createNode(tx);

            collapsedNodeParameters.put(replacement, resourceParameters);

            collapsedNodes.add(replacement);
        }

        //Link the replacement nodes together in the appropriate order.
        ListIterator<Node> it = collapsedNodes.listIterator();
        String query = "MATCH (n), (m) WHERE elementId(n) = '%s' AND elementId(m) = '%s' CREATE (n)-[:NEXT]->(m) ;";
        Node lastNode = null;
        while (it.hasNext()){

            Node curr = it.next();

            log.info("index: {}", it.previousIndex());

            if(it.previousIndex() == 0){ //If this is the first collapsed node, create an edge from the anchor
                tx.execute(query.formatted(startingAnchor.getElementId(), curr.getElementId()));
            }else{
                //Otherwise create an edge from the last node to the current node.
                tx.execute(query.formatted(lastNode.getElementId(), curr.getElementId()));
            }

            //If this node had resource parameters, re-attach them as well.
            if(collapsedNodeParameters.containsKey(curr)){
                for(Node param:  collapsedNodeParameters.get(curr)){

                    //Check if a :PARAM type relationship between this node and the target parameter already exist...
                    if(!curr.hasRelationship(Direction.OUTGOING,RelationshipType.withName("PARAM")) ){

                        //Only create one if not.
                        var parameterAttachmentQuery = "MATCH (n), (m) WHERE elementId(n) = '%s' and elementId(m) = '%s' CREATE (n)-[:PARAM]->(m);".formatted(curr.getElementId(), param.getElementId());
                        tx.execute(parameterAttachmentQuery);
                    }

                }

            }

            lastNode = curr;
        }

        //Create an edge from the last node to the end anchor.
        tx.execute(query.formatted(lastNode.getElementId(), endingAnchor.getElementId()));


    }

    private Collapse findCollapse(Transaction tx, Node startingNode){

        CollapsingEvaluator evaluator = new CollapsingEvaluator();

        TraversalDescription traversal = tx.traversalDescription()
                .breadthFirst()
                .uniqueness(Uniqueness.NODE_PATH)
                .relationships(RelationshipType.withName("NEXT"), Direction.OUTGOING)
                .evaluator(evaluator);

        Traverser traverser = traversal.traverse(startingNode);

        Iterator<Path> pathIterator = traverser.iterator();
        while (pathIterator.hasNext()){
            pathIterator.next();
            //log.info("Traverser path: {}", p.toString());
        }

        log.info("Collapse is: {}", evaluator.hasCollapse()?evaluator.getCollapse():"null");

        return evaluator.getCollapse();

    }

}
