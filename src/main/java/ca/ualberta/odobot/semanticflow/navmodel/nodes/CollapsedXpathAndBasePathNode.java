package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import ca.ualberta.odobot.common.BasePathAndXpath;
import ca.ualberta.odobot.common.Xpath;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class CollapsedXpathAndBasePathNode extends CollapsedNode{

    private static final Logger log = LoggerFactory.getLogger(CollapsedXpathAndBasePathNode.class);

    public Set<BasePathAndXpath> basePathsAndXpaths = new HashSet<>();

    public CollapsedXpathAndBasePathNode(Set<Node> nodeSet) {
        super(nodeSet);

        nodeSet.forEach(node->{

            if(node.hasProperty("xpath") && node.hasProperty("basePath")){
                BasePathAndXpath entry = new BasePathAndXpath((String)node.getProperty("basePath"), new Xpath((String)node.getProperty("xpath")));
                basePathsAndXpaths.add(entry);
            }

            if(node.hasProperty("xpaths")){
                basePathsAndXpaths.addAll(
                        Arrays.stream((String []) node.getProperty("xpaths")).map(BasePathAndXpath::fromString).collect(Collectors.toSet())
                );
            }
        });
    }

    public Node createNode(Transaction tx){

        Node result = super.createNode(tx);

        String [] propValue =  basePathsAndXpaths.stream().map(BasePathAndXpath::toString).collect(Collectors.toSet()).toArray(new String[0]);


        result.setProperty("xpaths", propValue);

        return result;

    }

    public Set<String> getXpathsForBasePath(String baseUri){
        return basePathsAndXpaths.stream().filter(entry->entry.getBasePath().equals(baseUri))
                .map(BasePathAndXpath::getXpath)
                .map(Xpath::toString)
                .collect(Collectors.toSet());
    }
}
