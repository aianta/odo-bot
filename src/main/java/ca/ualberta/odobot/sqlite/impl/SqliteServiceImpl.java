package ca.ualberta.odobot.sqlite.impl;

import ca.ualberta.odobot.sqlite.LogParser;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static ca.ualberta.odobot.logpreprocessor.Constants.SQLITE_CONNECTION_STRING;


public class SqliteServiceImpl implements SqliteService {

    private static final Logger log = LoggerFactory.getLogger(SqliteServiceImpl.class);
    private Vertx vertx;
    JDBCPool pool;

    public SqliteServiceImpl(Vertx vertx){
        this.vertx = vertx;
        JsonObject config = new JsonObject()
                .put("url", SQLITE_CONNECTION_STRING)
                .put("max_pool_size", 16);

        pool = JDBCPool.pool(vertx, config);

        createLogTable();
    }

    public Future<JsonArray> selectLogs(long timestampMilli, long range){
        Promise promise = Promise.promise();

        pool.preparedQuery("""
            SELECT * FROM logs WHERE timestamp_milli > ? AND timestamp_milli < ?;
        
        """).execute(Tuple.of(
                timestampMilli - range, //lowerbound
                timestampMilli + range  //upperbound
        )).onSuccess(results->{

            JsonArray logs = new JsonArray();

            results.forEach(row->logs.add(DbLogEntry.fromRow(row).toJson()));
            log.info("returning {} db logs for timestamp {} and range {}", logs.size(), timestampMilli, range);

            promise.complete(logs);
        }).onFailure(err->{
            log.error(err.getMessage(), err);
            promise.fail(err);
        })
        ;

        return promise.future();
    }

    public Future<Void> insertLogEntry(JsonObject json){
        return insertLogEntry(DbLogEntry.fromJson(json));
    }

    private Future<Void> insertLogEntry(DbLogEntry entry){
        Promise<Void> promise = Promise.promise();

        pool.preparedQuery("""
            INSERT INTO logs (key_value, timestamp, timestamp_milli, type, command, object_type, object_name, statement, parameter) VALUES 
            (?,?,?,?,?,?,?,?,?);
        """).execute(Tuple.of(
                entry.key(),
                entry.timestamp().format(LogParser.timestampFormat),
                entry.timestampMilli(),
                entry.type(),
                entry.command(),
                entry.objectType(),
                entry.objectName(),
                entry.statement(),
                entry.parameter()
        )).onSuccess(done->{
            promise.complete();
        }).onFailure(err->{
            if(err.getMessage().contains("A PRIMARY KEY constraint failed")){
                log.debug("LogEntry {} already exists in database, ignoring.", entry.key());
                promise.complete();
                return;
            }
            log.error(err.getMessage(), err);
            promise.fail(err);
        });

        return promise.future();
    }

    private Future<Void> createLogTable(){
        Promise<Void> promise = Promise.promise();

        pool.preparedQuery("""
            CREATE TABLE IF NOT EXISTS logs (
                key_value text PRIMARY KEY,
                timestamp text NOT NULL,
                timestamp_milli NUMERIC NOT NULL,
                type TEXT NOT NULL,
                command TEXT NOT NULL,
                object_type TEXT NOT NULL,
                object_name TEXT NOT NULL,
                statement TEXT NOT NULL,
                parameter TEXT NOT NULL
            )
        """).execute(result->{
            if(result.succeeded()){
                promise.complete();
            }else{
                promise.fail(result.cause());
            }
        });


        return promise.future();
    }

}
