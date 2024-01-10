package ca.ualberta.odobot.semanticflow.model.semantictrace.strategy;

import ca.ualberta.odobot.sqlite.SqliteService;

public abstract class AbstractStrategy {


    protected SqliteService sqliteService;


    public AbstractStrategy(SqliteService sqliteService){
        this.sqliteService = sqliteService;
    }

}
