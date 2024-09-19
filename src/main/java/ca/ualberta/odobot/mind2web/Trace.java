package ca.ualberta.odobot.mind2web;

import java.util.ArrayList;

public class Trace extends ArrayList<Operation> {

    private String website;
    private String domain;
    private String subdomain;
    private String annotationId;
    private String confirmedTask;
    private String actionRepresentation;

    public long numClicks(){
        return stream().filter(o->o instanceof Click).count();
    }

    public long numSelects(){
        return stream().filter(o->o instanceof SelectOption).count();
    }

    public long numTypes(){
        return stream().filter(o->o instanceof Type).count();
    }

    public String getWebsite() {
        return website;
    }

    public Trace setWebsite(String website) {
        this.website = website;
        return this;
    }

    public String getDomain() {
        return domain;
    }

    public Trace setDomain(String domain) {
        this.domain = domain;
        return this;
    }

    public String getSubdomain() {
        return subdomain;
    }

    public Trace setSubdomain(String subdomain) {
        this.subdomain = subdomain;
        return this;
    }

    public String getAnnotationId() {
        return annotationId;
    }

    public Trace setAnnotationId(String annotationId) {
        this.annotationId = annotationId;
        return this;
    }

    public String getConfirmedTask() {
        return confirmedTask;
    }

    public Trace setConfirmedTask(String confirmedTask) {
        this.confirmedTask = confirmedTask;
        return this;
    }

    public String getActionRepresentation() {
        return actionRepresentation;
    }

    public Trace setActionRepresentation(String actionRepresentation) {
        this.actionRepresentation = actionRepresentation;
        return this;
    }
}
