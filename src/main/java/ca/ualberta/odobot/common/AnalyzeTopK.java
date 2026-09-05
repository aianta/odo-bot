package ca.ualberta.odobot.common;

import ca.ualberta.odobot.sqlite.SqliteVectorService;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AnalyzeTopK {
    private static final Logger log = LoggerFactory.getLogger(AnalyzeTopK.class);
    SqliteVectorService vectorService;

    private JsonObject answerKey;



    public AnalyzeTopK(SqliteVectorService vectorService, Path answerKeyPath) {
        this.vectorService = vectorService;

        try{
            answerKey = new JsonObject(Buffer.buffer(Files.readAllBytes(answerKeyPath)));

            //The format of this JSON file is <trajectory ID>: <instance ID>, for convenience we want to inverse that.
            JsonObject inverse = new JsonObject();
            answerKey.stream()
                    .forEach(entry->inverse.put((String)entry.getValue(), entry.getKey()));
            answerKey = inverse;

            log.info("Answer key is loaded.");
        }catch(IOException e){
            log.error(e.getMessage(), e);
        }

    }



    public Future<JsonObject> analyzeTopK(List<String> inputFolders){


        return Future.all(
                inputFolders.stream().map(this::analyzeFolder).collect(Collectors.toList())
        ).compose(histogramsFuture->{
            List<JsonObject> histograms = histogramsFuture.list();


            if (histograms.size() > 1){
                ListIterator<JsonObject> it = histograms.listIterator();
                JsonObject finalResult = null;
                while(it.hasNext()){
                    if(finalResult == null){
                        finalResult = combineHistograms(it.next(), it.next());
                    }else{
                        finalResult = combineHistograms(finalResult, it.next());
                    }
                }

                return Future.succeededFuture(finalResult);
            }else{
                return Future.succeededFuture(histogramsFuture.resultAt(0));
            }
        });


    }

    private JsonObject combineHistograms(JsonObject h1, JsonObject h2){
        JsonObject result = new JsonObject();

        h1.stream().forEach(entry->{
            if(!entry.getKey().equals("mismatches")){
                var h1Value = h1.getInteger(entry.getKey());
                var h2Value = h2.getInteger(entry.getKey());
                result.put(entry.getKey(), h1Value + h2Value);
            }else{
                JsonArray mismatches = new JsonArray();
                mismatches.addAll(h1.getJsonArray("mismatches"));
                mismatches.addAll(h2.getJsonArray("mismatches"));
                result.put("mismatches", mismatches);
            }
        });

        return result;
    }

    private Future<JsonObject> analyzeFolder(String folderPath) {

        try{
            Set<Path> toDo = getTaskQueryConstructionFiles(folderPath);

            return Future.all(
                            toDo.stream()
                                    .map(this::analyzeTaskQueryConstructionResult)
                                    .toList())
                    .compose(compositeFuture -> {

                        JsonArray mismatches = compositeFuture.list().stream()
                                .map(JsonObject.class::cast)
                                .filter(obj->!obj.getString("correctTrajectoryId").equals(obj.getString("targetingTaskId")))
                                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);



                        int [] indicies = compositeFuture.list().stream()
                                .map(JsonObject.class::cast)
                                .mapToInt(result->result.getInteger("choiceIndex"))
                                .toArray();

                        JsonObject folderHistogram = new JsonObject();
                        //Initialize histogram for folder.
                        for (int i = -1; i < 50; i++) {
                            folderHistogram.put(Integer.toString(i), 0);
                        }

                        for(int choice: indicies){
                            int count = folderHistogram.getInteger(Integer.toString(choice));
                            folderHistogram.put(Integer.toString(choice), count + 1);
                        }

                        folderHistogram.put("mismatches", mismatches);

                        log.info("Histogram for folder: {}\n{}", folderPath, folderHistogram.encodePrettily());


                        return Future.succeededFuture(folderHistogram);
                    });
        }catch (IOException e){
            log.error(e.getMessage(),e);
            return Future.failedFuture(e);
        }

    }

    private Future<JsonObject> analyzeTaskQueryConstructionResult(Path taskQueryConstructionLog) {

        try{
            JsonObject taskQueryResult = new JsonObject(Buffer.buffer(Files.readAllBytes(taskQueryConstructionLog)));

            String taskId = taskQueryResult.getString("id");
            String query = taskQueryResult.getString("rewrittenTo");
            String chosenTaskId =  taskQueryResult.getJsonArray("targets").getJsonObject(0).getString("targetingTaskId");


            return vectorService.topK(100, query).compose(results->{
                ListIterator<JsonObject> resultIterator = results.listIterator();
                String correctTrajectoryId = answerKey.getString(taskId);
                while (resultIterator.hasNext()) {
                    JsonObject result = resultIterator.next();

                    if(result.getString("trajectoryId").equals(correctTrajectoryId)){

                        JsonObject choice = new JsonObject()
                                .put("taskId", taskId)
                                .put("targetingTaskId", chosenTaskId)
                                .put("correctTrajectoryId", correctTrajectoryId)
                                .put("rewrittenTo", query)
                                .put("choiceIndex", resultIterator.previousIndex()+1)
                                .put("totalChoices", results.size());

                        return Future.succeededFuture(choice);
                    }
                }

                JsonObject choice = new JsonObject()
                        .put("taskId", taskId)
                        .put("targetingTaskId", chosenTaskId)
                        .put("correctTrajectoryId", correctTrajectoryId)
                        .put("rewrittenTo", query)
                        .put("choiceIndex", -1)
                        .put("totalChoices", results.size());

                return Future.succeededFuture(choice);
            });
        }catch (IOException exception){
            log.error(exception.getMessage(), exception);
            return Future.failedFuture(exception);
        }



    }

    private Set<Path> getTaskQueryConstructionFiles(String folderPath) throws IOException {
        Path dirPath = Paths.get(folderPath);

        try(Stream<Path> paths = Files.walk(dirPath)){
            return paths.filter(Files::isRegularFile)
                    .filter(f->f.getFileName().toString().contains("-task-query-construction-result.json"))
                    .collect(Collectors.toSet());
        }
    }

}
