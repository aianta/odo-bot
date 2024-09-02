package ca.ualberta.odobot.semanticflow.navmodel;


import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.graphdb.GraphDatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Creates and facilitates access to an embedded neo4J database.
 */
public class GraphDB {

    private static final Logger log = LoggerFactory.getLogger(GraphDB.class);

    private final String databaseName;
    private final Path databaseDirectory;
    private final DatabaseManagementService managementService;
    public final GraphDatabaseService db;

    public GraphDB(String databaseDirectory, String databaseName){
        this.databaseDirectory = Path.of(databaseDirectory);
        this.databaseName = databaseName;

        managementService = new DatabaseManagementServiceBuilder(this.databaseDirectory)
                //Set up bolt connector to allow use of Neo4J Browser.
                .setConfig(BoltConnector.enabled, true)
                .setConfig(GraphDatabaseSettings.data_directory,  Path.of("data-3"))
                .setConfig(GraphDatabaseSettings.auth_enabled, false)
                .setConfig(BoltConnector.listen_address, new SocketAddress("localhost", 7687))
                .build();


        db = managementService.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME);

        log.info("Embedded Neo4J started!");
    }





}
