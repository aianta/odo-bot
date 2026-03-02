package ca.ualberta.odobot.guidance.execution;

public class ResourceParameter extends ExecutionParameter {

    private String query;

    private String name;

    public String getQuery(){
        return query;
    }

    public String getName() {
        return name;
    }

    public ResourceParameter setName(String name) {
        this.name = name;
        return this;
    }

    public ResourceParameter setQuery(String query) {
        this.query = query;
        return this;
    }
}
