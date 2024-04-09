package ca.ualberta.odobot.semanticflow.navmodel;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class NavNode {

    private UUID id;

    private Set<String> instances = new HashSet<>();

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
