package ca.ualberta.odobot.sqlite.impl;

import ca.ualberta.odobot.sqlite.SqliteVectorService;
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.EmbeddingItem;
import com.azure.ai.openai.models.Embeddings;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.ai.openai.models.EmbeddingsUsage;
import com.azure.core.credential.KeyCredential;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.nio.ByteBuffer;
import java.sql.*;
import java.util.*;

public class SqliteVectorServiceImpl  implements SqliteVectorService {

    private static final Logger log = LoggerFactory.getLogger(SqliteVectorServiceImpl.class);

    private Connection connection;

    private JsonObject config;

    private OpenAIClient openAIClient;

    public SqliteVectorServiceImpl(JsonObject config) {
        this.config = config;
        init();
    }

    private void init() {
        log.info("Initializing SqliteVectorServiceImpl...");

        log.info("Setting up openAI client...");
        //Setup the OpenAI client for generating vector embeddings.
        openAIClient = new OpenAIClientBuilder()
                .credential(new KeyCredential(this.config.getString("secretKey")))
                .buildClient();

        //Setup SQLite with the vector extension.
        try {
            Properties props = new Properties();
            props.setProperty("enable_load_extension", "true");

            log.info("Establishing connection to SQLite database at {}...", config.getString("databasePath"));
            connection = DriverManager.getConnection("jdbc:sqlite:" + config.getString("databasePath"), props);

            log.info("Loading SQLite vector extension from {}...", config.getString("extensionPath"));
            connection.createStatement().execute("SELECT load_extension('%s')".formatted(config.getString("extensionPath")));

            log.info("Creating {} table if not exists...", config.getString("syntheticTaskVectorTable"));
            createSyntheticTaskVectorTable();

            log.info("Preparing any existing vectors for querying...");
            readyVectorsForQuerying();
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

    }

    public Future<Void> embedSyntheticTasks(JsonObject tasks) {


        //new EmbeddingsOptions()

        return Future.succeededFuture();
    }


    public Future<Void> readyVectorsForQuerying() {
        try(Statement statement = connection.createStatement()){
            //Following directions from https://github.com/sqliteai/sqlite-vector

            //Initialize the vectors
            statement.executeQuery("SELECT vector_init('%s', 'embedding', 'type=%s,dimension=%s,distance=%s')".formatted(
                    config.getString("syntheticTaskVectorTable"),
                    config.getString("type"),
                    config.getInteger("dimensions").toString(),
                    config.getString("distance")
                    ));

            //Quantize the vectors
            statement.executeQuery("SELECT vector_quantize('%s', 'embedding')".formatted(config.getString("syntheticTaskVectorTable")));

            //Load quantized version into memory
            statement.executeQuery("SELECT vector_quantize_preload('%s', 'embedding')".formatted(config.getString("syntheticTaskVectorTable")));


        } catch (SQLException e) {
            log.error(e.getMessage(), e);
            return Future.failedFuture(e);
        }

        return Future.succeededFuture();
    }

    public Future<List<JsonObject>> topK(int k, String queryString){

        EmbeddingsOptions options = new EmbeddingsOptions(List.of(queryString));
        options.setDimensions(config.getInteger("dimensions"));
        Embeddings embeddings = openAIClient.getEmbeddings(config.getString("embeddingModel"), options);
        EmbeddingItem item = embeddings.getData().get(0);

        ByteBuffer byteBuffer = ByteBuffer.allocate(item.getEmbedding().size()*4);
        for(Float f: item.getEmbedding()){
            byteBuffer.putFloat(f);
        }
        byte[] queryVector = byteBuffer.array();

        String sql = """
                    SELECT e.trajectory_id, v.distance FROM %s AS e
                    JOIN vector_quantize_scan('%s','embedding', vector_as_f32(?), ?) AS v
                    ON e.rowid = v.rowid;
                    """.formatted(config.getString("syntheticTaskVectorTable"), config.getString("syntheticTaskVectorTable"));
        log.info("{}", sql);
        try(PreparedStatement stmt = connection.prepareStatement(sql);){
            stmt.setString(1, item.getEmbedding().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll ).encode());
            stmt.setInt(2, k);

            ResultSet rs = stmt.executeQuery();

            List<JsonObject> topKTrajectories = new ArrayList<>();
            while (rs.next()){
                topKTrajectories.add(
                        new JsonObject()
                                .put("trajectoryId", rs.getString("trajectory_id"))
                                .put("distance", rs.getFloat("distance")));

                log.info("trajectory: {} distance: {}", rs.getString("trajectory_id"), rs.getString("distance"));
            }

            return Future.succeededFuture(topKTrajectories);


        }catch (SQLException e){
            log.error(e.getMessage(),e);
            return Future.failedFuture(e);
        }

    }

    public Future<Void> embedSyntheticTask(String trajectoryId, String task) {
        log.info("Computing embedding for synthetic task for trajectory {}:\n{}", trajectoryId, task);

        EmbeddingsOptions options = new EmbeddingsOptions(List.of(task));
        options.setDimensions(config.getInteger("dimensions"));
        Embeddings embeddings = openAIClient.getEmbeddings(config.getString("embeddingModel"), options);

        for (EmbeddingItem item : embeddings.getData()) {
            log.info("Embedding vector of length: {}", item.getEmbedding().size());
            log.info("Got embedding: {}", item.getEmbedding().subList(0,10));
            var byteBuffer = ByteBuffer.allocate(item.getEmbedding().size()*4);
            for (float f :item.getEmbedding()){
                byteBuffer.putFloat(f);
            }
            byte [] vector = byteBuffer.array();
            try(Statement stmt = connection.createStatement()){
                String sql = """
                        INSERT INTO %s (trajectory_id, embedding) VALUES (?, vector_as_f32(?));
                        """.formatted(config.getString("syntheticTaskVectorTable"));
                var ps = connection.prepareStatement(sql);
                ps.setString(1, trajectoryId);
                ps.setString(2, item.getEmbedding().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll ).encode());
                ps.execute();
            }catch (SQLException e){
                log.error(e.getMessage(), e);
                return Future.failedFuture(e);
            }
        }

        EmbeddingsUsage usage = embeddings.getUsage();
        log.info("Usage: number of prompt token is {}, and number of total tokens in request and response is {}",
                usage.getPromptTokens(), usage.getPromptTokens());

        return Future.succeededFuture();

    }

    private void createSyntheticTaskVectorTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS %s (
                    trajectory_id TEXT PRIMARY KEY,
                    embedding BLOB
                );
                """.formatted(config.getString("syntheticTaskVectorTable"));

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }


    }
}