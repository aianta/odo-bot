package ca.ualberta.odobot.taskgenerator.canvas;

import ca.ualberta.odobot.common.AbstractOpenAIStrategy;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * This is a utility class that generates parameterized tasks for Canvas.
 * It works by first using the canvas documentation to generate tasks.
 * And then parameterizing the generated tasks.
 */
public class GenerateParameterizedTasks extends AbstractOpenAIStrategy {

    private static final Logger log = LoggerFactory.getLogger(GenerateParameterizedTasks.class);
    private static final String configPath = "./config/parameterized-task-generator.yaml";
    private static final String canvasRootUrl = "https://community.canvaslms.com";

    private List<CanvasSourceFile> canvasSourceFiles;

    public static void main (String [] args){

        //Init a vertx instance to retrieve yaml config
        Vertx vertx = Vertx.builder()
                .with(new VertxOptions()
                        .setBlockedThreadCheckInterval(1)
                        .setBlockedThreadCheckIntervalUnit(TimeUnit.HOURS)
                )
                .build();

        ConfigStoreOptions yamlStore = new ConfigStoreOptions()
                .setType("file")
                .setFormat("yaml")
                .setConfig(
                        new JsonObject()
                                //Use the hard coded config file path if one is not provided as a parameter.
                                .put("path", args.length < 1? configPath: args[0])
                );
        ConfigRetrieverOptions retrieverOptions = new ConfigRetrieverOptions()
                .addStore(yamlStore);
        ConfigRetriever retriever = ConfigRetriever.create(vertx, retrieverOptions);

        //Once the config retriever is set up, fetch the config and initialize the task generator
        retriever.getConfig()
                .onFailure(err->log.error(err.getMessage(), err))
                .onSuccess(_config->{

                    GenerateParameterizedTasks generator = new GenerateParameterizedTasks(_config);
                    List<CanvasTask> tasks = generator.generateTasks();
                    //generator.saveTasks(tasks, _config.getString("canvasTasksOutputDir"));
                    tasks = generator.generateParameterizedTasks(tasks);
                    generator.saveTasks(tasks, _config.getString("canvasTasksOutputDir"));

                    log.info("Done!");
                });

    }
    public GenerateParameterizedTasks(JsonObject config){
        super(config);
        canvasSourceFiles = loadCanvasSourceFiles(config.getString("canvasUserManualPath"));
    }

    public void saveTasks(List<CanvasTask> tasks, String outputDir) {

        try{
            for(JsonObject task: tasks){
                Path taskPath = Path.of(outputDir + "/" + task.getString("id") + ".json");

                if(Files.exists(taskPath)){//If the file already exists. Delete and rewrite it!
                    Files.delete(taskPath);
                }

                Files.writeString(taskPath, task.encodePrettily());
            }
        }catch (IOException e){
            log.error(e.getMessage(), e);
        }


    }

    public List<CanvasTask> generateParameterizedTasks(List<CanvasTask> tasks){

        JsonObject parameterizedTaskGenerationConfig = config.getJsonObject("canvas").getJsonObject("parameterizedTaskGeneration");

        for(CanvasTask task: tasks){
            CanvasSourceFile sourceFile = CanvasSourceFile.loadFromFile(task.getLocalPath());

            String systemPrompt = parameterizedTaskGenerationConfig.getString("systemPrompt");
            systemPrompt = systemPrompt.replaceAll("<task>", task.getPlainTask());
            systemPrompt = systemPrompt.replaceAll("<user manual page body>", sourceFile.getBody());

            task.setParameterizedTaskPrompt(systemPrompt);

            List<ChatRequestMessage> chatMessages = new ArrayList<>();

            chatMessages.add(new ChatRequestSystemMessage(systemPrompt));

            String output = executeChatCompletion(chatMessages);

            task.setParameterizedTask(output);
            task.setParameterizedTaskPrompt(systemPrompt);
        }

        return tasks;
    }

    public List<CanvasTask> generateTasks(){
        if(canvasSourceFiles == null){
            log.error("Cannot generate tasks because canvas source files are not available (null!).");
        }

        JsonObject taskGenerationConfig = config.getJsonObject("canvas").getJsonObject("taskGeneration");

        //Init a list to store the generated tasks.
        List<CanvasTask> tasks = new ArrayList<>();

        for(CanvasSourceFile page: canvasSourceFiles){

            StringBuilder promptAudit = new StringBuilder();

            String systemPrompt = taskGenerationConfig.getString("systemPrompt");

            List<ChatRequestMessage> chatMessages = new ArrayList<>();

            chatMessages.add(new ChatRequestSystemMessage(systemPrompt));
            promptAudit.append(systemPrompt);

            chatMessages.add(new ChatRequestUserMessage(page.getTitle()));
            promptAudit.append(page.getTitle());

            String output = executeChatCompletion(chatMessages);

            CanvasTask canvasTask = new CanvasTask();
            canvasTask.setId(UUID.randomUUID());
            canvasTask.setLocalPath(page.getLocalPath());
            canvasTask.setPlainTask(output);
            canvasTask.setPlainTaskPrompt(promptAudit.toString());

            tasks.add(canvasTask);

        }

        return tasks;

    }

    private List<CanvasSourceFile> loadCanvasSourceFiles(String dirPath){
        Path canvasRootPath = Path.of(dirPath);
        Path canvasTableOfContentsPath = Path.of(canvasRootPath + "/toc.html");

        if(!Files.exists(canvasRootPath)){
            log.error("The given directory path ({}) for the canvas user doc files does not exist!", dirPath);
            return null;
        }

        if(!Files.exists(canvasTableOfContentsPath)){
            log.error("The canvas table of contents file does not exist! Looked for it in {}", canvasTableOfContentsPath.toString());
            return null;
        }

        try{

            String tocHTML = new String(Files.readAllBytes(canvasTableOfContentsPath));
            Document tableOfContents = Jsoup.parse(tocHTML);

            return getManualPages(canvasRootPath, tableOfContents);

        }catch (IOException e){
            log.error(e.getMessage(), e);
        }

        log.error("Something went wrong!");
        return null;
    }

    private List<CanvasSourceFile> getManualPages(Path canvasRootPath, Document tableOfContents) throws IOException {
        List<CanvasSourceFile> result = new ArrayList<>();

        Iterator<Element> it = tableOfContents.select(".toc-main>section>ul>li>a").iterator();
        while (it.hasNext()){
            Element anchorTag = it.next();
            CanvasSourceFile page = new CanvasSourceFile();
            page.setUrl(canvasRootUrl + anchorTag.attribute("href").getValue());
            page.setTitle(anchorTag.text());

            Path pagePath = Path.of(canvasRootPath + "/" + page.getFileSafeTitle() + ".html");
            log.info("Loading {}", pagePath.toString());
            page.setLocalPath(pagePath.toString());

            String pageData = new String(Files.readAllBytes(pagePath));
            Document pageDocument = Jsoup.parse(pageData);

            page.setBody(pageDocument.select(".lia-message-body-content").first().text());

            result.add(page);
        }

        return result;
    }
}
