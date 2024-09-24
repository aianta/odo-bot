package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;

public class NavNode {

    public static <T extends NavNode> T fromRecord(Record record, T out){
        Node node = record.get(0).asNode();

        if(!node.get("website").isNull()){
            out.setWebsite(node.get("website").asString());
        }

        out.setId(UUID.fromString(node.get("id").asString()));
        out.setInstances(node.get("instances").asList().stream().map(o->(String)o).collect(Collectors.toSet()));

        return out;
    }

    private UUID id;

    private Set<String> instances = new HashSet<>();

    private String website;

    public String getWebsite() {
        return website;
    }

    public NavNode setWebsite(String website) {
        this.website = website;
        return this;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Set<String> getInstances() {
        return instances;
    }

    public void setInstances(Set<String> instances) {
        this.instances = instances;
    }
}
