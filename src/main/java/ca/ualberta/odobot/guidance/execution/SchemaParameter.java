package ca.ualberta.odobot.guidance.execution;

public class SchemaParameter extends ExecutionParameter{

    private String query;

    public String getQuery() {
        return query;
    }

    public SchemaParameter setQuery(String query) {
        this.query = query;
        return this;
    }
}
