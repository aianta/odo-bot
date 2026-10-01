package ca.ualberta.odobot.sqlite.impl;

import ca.ualberta.odobot.common.LlmCallType;
import ca.ualberta.odobot.guidance.TokenUsageRecord;
import ca.ualberta.odobot.sqlite.SqliteVectorService;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
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
        openAIClient = OpenAIOkHttpClient.builder()
                .apiKey(this.config.getString("secretKey"))
                .build();

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

        CreateEmbeddingResponse embeddings = createEmbeddings(LlmCallType.SIMILAR_TASK_EMBEDDING, queryString);
        Embedding item = embeddings.data().get(0);

        ByteBuffer byteBuffer = ByteBuffer.allocate(item.embedding().size()*4);
        for(Float f: item.embedding()){
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
            stmt.setString(1, item.embedding().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll ).encode());
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

        CreateEmbeddingResponse embeddings = createEmbeddings(LlmCallType.OTHER_EMBEDDING, task);

        for (Embedding item : embeddings.data()) {
            log.info("Embedding vector of length: {}", item.embedding().size());
            log.info("Got embedding: {}", item.embedding().subList(0,10));
            var byteBuffer = ByteBuffer.allocate(item.embedding().size()*4);
            for (float f :item.embedding()){
                byteBuffer.putFloat(f);
            }
            byte [] vector = byteBuffer.array();
            try(Statement stmt = connection.createStatement()){
                String sql = """
                        INSERT INTO %s (trajectory_id, embedding) VALUES (?, vector_as_f32(?));
                        """.formatted(config.getString("syntheticTaskVectorTable"));
                var ps = connection.prepareStatement(sql);
                ps.setString(1, trajectoryId);
                ps.setString(2, item.embedding().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll ).encode());
                ps.execute();
            }catch (SQLException e){
                log.error(e.getMessage(), e);
                return Future.failedFuture(e);
            }
        }

        CreateEmbeddingResponse.Usage usage = embeddings.usage();
        log.info("Usage: number of prompt token is {}, and number of total tokens in request and response is {}",
                usage.promptTokens(), usage.totalTokens());

        return Future.succeededFuture();

    }

    /**
     * @param callType the context of this call, used to break down token usage.
     */
    private CreateEmbeddingResponse createEmbeddings(LlmCallType callType, String input){
        CreateEmbeddingResponse response = openAIClient.embeddings().create(EmbeddingCreateParams.builder()
                .model(config.getString("embeddingModel"))
                .input(input)
                .dimensions(config.getInteger("dimensions"))
                .build());

        //Embeddings have no output tokens.
        TokenUsageRecord.report(callType, response.usage().promptTokens(), 0, response.usage().totalTokens());
        return response;
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