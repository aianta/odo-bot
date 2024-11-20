package ca.ualberta.odobot.mind2web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Trajectory {

    private static final Logger log = LoggerFactory.getLogger(Trajectory.class);

    private String name;
    private String annotationId;

    private List<String> actions = new ArrayList<>();

    private Iterator<String> iterator;


    public String getName() {
        return name;
    }

    public Trajectory setName(String name) {
        this.name = name;
        return this;
    }

    public String getAnnotationId() {
        return annotationId;
    }

    public Trajectory setAnnotationId(String annotationId) {
        this.annotationId = annotationId;
        return this;
    }

    public List<String> getActions() {
        return actions;
    }

    public Trajectory setActions(List<String> actions) {
        this.actions = actions;
        this.iterator = actions.iterator();
        return this;
    }

    public String nextActionId(){
        return this.iterator.next();
    }


}
