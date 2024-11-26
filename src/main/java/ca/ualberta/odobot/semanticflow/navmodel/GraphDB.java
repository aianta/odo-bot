package ca.ualberta.odobot.semanticflow.navmodel;


import apoc.ApocGlobalComponents;
import apoc.CoreApocGlobalComponents;
import apoc.ExtendedApocGlobalComponents;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.graphdb.GraphDatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Creates and facilitates access to an embedded neo4J database.
 */
public class GraphDB {

    private static final Logger log = LoggerFactory.getLogger(GraphDB.class);

    private final String databaseName;
    private final Path databaseDirectory;
    private DatabaseManagementService managementService;
    public GraphDatabaseService db;


    public GraphDB(String databaseDirectory, String databaseName) throws URISyntaxException, IOException {
        this.databaseDirectory = Path.of(databaseDirectory);
        this.databaseName = databaseName;

        log.info("Starting Embedded Neo4j");
        try{
            /**
             * We want to register APOC procedures to facilitate some functionality like adding dynamic value node labels in cypher queries.
             * APOC also provides a number of helper procedures implementing common graph algorithms.
             *
             * Following the example below to facilitate APOC procedure registration with embedded neo4j.
             * https://github.com/michael-simons/neo4j-examples-and-tips/blob/master/examples/testing-ogm-against-embedded-with-apoc/src/test/java/org/neo4j/tips/testing/testing_ogm_against_embedded_with_apoc/ApplicationTests.java#L53-L67
             */

            var pluginDir = Path.of("neo4j-plugins").toAbsolutePath();

            log.info("Copying over apoc jar(s) into neo4j-plugins folder");
            getDirsContainingApocJars().forEach(parentDir-> {
                try {
                    GraphDB.copyApocJarToPluginDir(parentDir, pluginDir);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });


            managementService = new DatabaseManagementServiceBuilder(this.databaseDirectory)
                    //Set up bolt connector to allow use of Neo4J Browser.
                    .setConfig(BoltConnector.enabled, true)
                    .setConfig(GraphDatabaseSettings.data_directory,  Path.of("data"))
                    .setConfig(GraphDatabaseSettings.auth_enabled, false)
                    .setConfig(GraphDatabaseSettings.debug_log_enabled, true)
                    .setConfig(GraphDatabaseSettings.logs_directory, Path.of("neo4j-logs").toAbsolutePath())
                    .setConfig(GraphDatabaseSettings.plugin_dir, pluginDir)
                    .setConfig(GraphDatabaseSettings.procedure_allowlist, List.of("apoc.*"))
                    .setConfig(GraphDatabaseSettings.procedure_unrestricted, List.of("apoc.*"))
                    .setConfig(BoltConnector.listen_address, new SocketAddress("localhost", 7687))
                    .build();


            db = managementService.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME);


            log.info("Embedded Neo4J started!");
        }catch (Exception e){
            log.error("Error starting embedded neo4j.");
            log.error(e.getMessage(), e);
        }


    }


    private static void copyApocJarToPluginDir(Path parentDir, Path pluginDir) throws IOException {
        Files.find(parentDir, 1, (p,a)->p.getFileName().toString().startsWith("apoc-"))
                .forEach(p->{
                    try{
                        Files.copy(p, pluginDir.resolve(p.getFileName()), REPLACE_EXISTING);
                    }catch (IOException e){
                        log.error("Error copying {} to {}", p.getFileName().toString(), pluginDir.resolve(p.getFileName()));
                        log.error(e.getMessage(), e);
                    }
                });
    }

    private static List<Path> getDirsContainingApocJars() throws URISyntaxException {

        return List.of(
                new File(ExtendedApocGlobalComponents.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile().toPath(),
                new File(ApocGlobalComponents.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile().toPath(),
                new File(CoreApocGlobalComponents.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile().toPath()
        );

    }




}
