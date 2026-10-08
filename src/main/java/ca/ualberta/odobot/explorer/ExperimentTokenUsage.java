package ca.ualberta.odobot.explorer;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Experiment-level token usage and wall clock time, computed from the {@code <evalId>-tokens.json} files the
 * RequestManager writes for each task into the experiment folder.
 */
public final class ExperimentTokenUsage {

    private static final Logger log = LoggerFactory.getLogger(ExperimentTokenUsage.class);

    static final String TASK_FILE_SUFFIX = "-tokens.json";

    private static final List<String> METRICS = List.of("inputTokens", "outputTokens", "totalTokens", "llmCalls", "failedCalls", "failedAttempts");

    private static final List<String> COUNTS = List.of("llmCalls", "inputTokens", "outputTokens", "totalTokens", "failedCalls", "inferenceMs");

    private static final List<String> TOKEN_METRICS = List.of("inputTokens", "outputTokens", "totalTokens");

    /**
     * The task phases timed by {@link ca.ualberta.odobot.guidance.TaskTiming}. Execution is split into
     * {@code inferenceMs} and {@code otherExecutionMs}.
     */
    private static final List<String> PHASES = List.of("setupMs", "executionMs", "inferenceMs", "otherExecutionMs", "artifactsMs", "scoringMs", "totalMs");

    private ExperimentTokenUsage(){}

    /**
     * Summarize the token usage and timing of every task in the experiment folder, add the wall clock time of the
     * experiment, and save the summary as {@code <experimentId>-tokens.json} in the results folder.
     *
     * @param skippedTasks the tasks that were not run because the experiment folder already had their outputs.
     * @return the summary.
     */
    public static JsonObject summarizeAndSave(String experimentId, Instant experimentStart, Instant experimentEnd, int skippedTasks,
                                              String experimentFolderPath, String experimentResultsFolderPath){
        JsonObject summary = new JsonObject().put("experimentId", experimentId).mergeIn(summarize(experimentFolderPath));
        JsonObject timing = summary.getJsonObject("timing")
                .put("experimentStart", experimentStart.toString())
                .put("experimentEnd", experimentEnd.toString())
                .put("wallClockMs", Duration.between(experimentStart, experimentEnd).toMillis())
                .put("skippedTasks", skippedTasks);

        log.info("Experiment {} token usage over {} tasks: {} total tokens ({} input, {} output) over {} LLM calls; per task min {}, max {}, mean {} total tokens",
                experimentId, summary.getInteger("taskCount"),
                summary.getJsonObject("totalTokens").getLong("sum"),
                summary.getJsonObject("inputTokens").getLong("sum"),
                summary.getJsonObject("outputTokens").getLong("sum"),
                summary.getJsonObject("llmCalls").getLong("sum"),
                summary.getJsonObject("totalTokens").getInteger("min"),
                summary.getJsonObject("totalTokens").getInteger("max"),
                summary.getJsonObject("totalTokens").getDouble("mean"));
        log.info("Experiment {} took {} ms of wall clock time ({} tasks timed, {} skipped); per task min {}, max {}, mean {} ms of execution, mean {} ms in total",
                experimentId, timing.getLong("wallClockMs"), timing.getInteger("taskCount"), skippedTasks,
                timing.getJsonObject("executionMs").getLong("min"),
                timing.getJsonObject("executionMs").getLong("max"),
                timing.getJsonObject("executionMs").getDouble("mean"),
                timing.getJsonObject("totalMs").getDouble("mean"));
        log.info("Experiment {} execution: {} ms waiting on inference, {} ms other work (inference share {})",
                experimentId, timing.getJsonObject("inferenceMs").getLong("sum"),
                timing.getJsonObject("otherExecutionMs").getLong("sum"), timing.getDouble("inferenceShare"));

        try{
            Files.createDirectories(Path.of(experimentResultsFolderPath));
            Files.writeString(Path.of(experimentResultsFolderPath, experimentId + TASK_FILE_SUFFIX), summary.encodePrettily());
        }catch (IOException e){
            log.error("Failed to save token usage for experiment {}: {}", experimentId, e.getMessage());
        }
        return summary;
    }

    /**
     * @param experimentFolderPath the folder whose {@code *-tokens.json} files are summarized. Subfolders are not searched.
     */
    public static JsonObject summarize(String experimentFolderPath){
        List<JsonObject> tasks = readTaskUsage(Path.of(experimentFolderPath));
        int taskCount = tasks.size();

        JsonObject summary = new JsonObject().put("taskCount", taskCount);

        for(String metric: METRICS){
            summary.put(metric, stats(tasks.stream().map(task->task.getLong(metric, 0L)).toList()));
        }

        tasks.stream().min(Comparator.comparingInt(task->task.getInteger("totalTokens", 0)))
                .ifPresent(task->summary.put("minTotalTokensEvalId", task.getString("evalId")));
        tasks.stream().max(Comparator.comparingInt(task->task.getInteger("totalTokens", 0)))
                .ifPresent(task->summary.put("maxTotalTokensEvalId", task.getString("evalId")));

        summary.put("byKind", summarizeGroups(tasks, "byKind"));
        summary.put("byCallType", summarizeGroups(tasks, "byCallType"));

        summary.put("timing", summarizeTiming(tasks));

        summary.put("tasks", tasks.stream()
                .map(task->{
                    JsonObject entry = new JsonObject()
                            .put("evalId", task.getString("evalId"))
                            .put("mode", task.getString("mode"))
                            .put("outcome", task.getString("outcome"))
                            .put("totalTokens", task.getInteger("totalTokens", 0))
                            .put("llmCalls", task.getInteger("llmCalls", 0));
                    JsonObject timing = task.getJsonObject("timing");
                    if(timing != null){
                        for(String phase: List.of("executionMs", "inferenceMs", "totalMs")){
                            if(timing.containsKey(phase)) entry.put(phase, timing.getLong(phase));
                        }
                    }
                    return entry;
                })
                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll));

        return summary;
    }

    /**
     * Summarize the timing of the tasks that have one (task files from before timing was recorded have none):
     * <ul>
     *     <li>min, max, mean and sum of each phase, over the tasks that reached it,</li>
     *     <li>{@code inferenceShare}: the fraction of execution spent waiting on inference, over the tasks that have the split,</li>
     *     <li>the fastest and slowest task by execution time,</li>
     *     <li>{@code byOutcome}: the task count, execution time and inference time stats of completed and failed tasks.</li>
     * </ul>
     */
    private static JsonObject summarizeTiming(List<JsonObject> tasks){
        List<JsonObject> timed = tasks.stream().filter(task->task.getJsonObject("timing") != null).toList();

        JsonObject timing = new JsonObject().put("taskCount", timed.size());
        for(String phase: PHASES){
            timing.put(phase, phaseStats(timed, phase));
        }

        List<JsonObject> split = timed.stream()
                .map(task->task.getJsonObject("timing"))
                .filter(t->t.containsKey("executionMs") && t.containsKey("inferenceMs"))
                .toList();
        long splitExecution = split.stream().mapToLong(t->t.getLong("executionMs")).sum();
        long splitInference = split.stream().mapToLong(t->t.getLong("inferenceMs")).sum();
        timing.put("inferenceShare", splitExecution == 0? 0.0 : (double) splitInference / splitExecution);

        Comparator<JsonObject> byExecution = Comparator.comparingLong(task->task.getJsonObject("timing").getLong("executionMs"));
        List<JsonObject> executed = timed.stream().filter(task->task.getJsonObject("timing").containsKey("executionMs")).toList();
        executed.stream().min(byExecution).ifPresent(task->timing.put("fastestEvalId", task.getString("evalId")));
        executed.stream().max(byExecution).ifPresent(task->timing.put("slowestEvalId", task.getString("evalId")));

        JsonObject byOutcome = new JsonObject();
        timed.stream()
                .collect(Collectors.groupingBy(ExperimentTokenUsage::outcomeGroup, TreeMap::new, Collectors.toList()))
                .forEach((outcome, group)->byOutcome.put(outcome, new JsonObject()
                        .put("taskCount", group.size())
                        .put("executionMs", phaseStats(group, "executionMs"))
                        .put("inferenceMs", phaseStats(group, "inferenceMs"))));
        timing.put("byOutcome", byOutcome);

        return timing;
    }

    private static JsonObject phaseStats(List<JsonObject> timedTasks, String phase){
        return stats(timedTasks.stream()
                .map(task->task.getJsonObject("timing"))
                .filter(timing->timing.containsKey(phase))
                .map(timing->timing.getLong(phase))
                .toList());
    }

    /**
     * @return {@code completed}, {@code failed} (whatever the reason) or {@code unknown}.
     */
    private static String outcomeGroup(JsonObject task){
        String outcome = task.getString("outcome");
        if(outcome == null) return "unknown";
        return outcome.startsWith("failed")? "failed" : outcome;
    }

    /**
     * Summarize the groups (call types or kinds) under {@code field} of every task. Each group gets:
     * <ul>
     *     <li>its call count, failed calls, token sums and summed call durations ({@code inferenceMs}) over all tasks,</li>
     *     <li>{@code perCall}: min, max and mean tokens of a single call,</li>
     *     <li>{@code perTask}: min, max and mean calls and tokens per task, counting tasks without that group as 0.</li>
     * </ul>
     */
    private static JsonObject summarizeGroups(List<JsonObject> tasks, String field){
        Set<String> keys = new TreeSet<>();
        tasks.forEach(task->keys.addAll(task.getJsonObject(field, new JsonObject()).fieldNames()));

        JsonObject groups = new JsonObject();
        for(String key: keys){
            //One entry per task, null when the task has no calls in this group.
            List<JsonObject> entries = tasks.stream().map(task->task.getJsonObject(field, new JsonObject()).getJsonObject(key)).toList();
            List<JsonObject> present = entries.stream().filter(Objects::nonNull).toList();

            JsonObject group = new JsonObject();
            present.stream().map(entry->entry.getString("kind")).filter(Objects::nonNull).findFirst()
                    .ifPresent(kind->group.put("kind", kind));

            for(String count: COUNTS){
                group.put(count, present.stream().mapToLong(entry->entry.getLong(count, 0L)).sum());
            }

            long calls = group.getLong("llmCalls");
            JsonObject perCall = new JsonObject();
            for(String metric: TOKEN_METRICS){
                List<JsonObject> callStats = present.stream()
                        .filter(entry->entry.getLong("llmCalls", 0L) > 0)
                        .map(entry->entry.getJsonObject("perCall", new JsonObject()).getJsonObject(metric))
                        .filter(Objects::nonNull)
                        .toList();
                perCall.put(metric, new JsonObject()
                        .put("min", callStats.stream().mapToLong(stat->stat.getLong("min")).min().orElse(0))
                        .put("max", callStats.stream().mapToLong(stat->stat.getLong("max")).max().orElse(0))
                        .put("mean", calls == 0? 0.0 : (double) group.getLong(metric) / calls));
            }
            group.put("perCall", perCall);

            JsonObject perTask = new JsonObject();
            for(String count: COUNTS){
                JsonObject stats = stats(entries.stream().map(entry->entry == null? 0L : entry.getLong(count, 0L)).toList());
                stats.remove("sum");
                perTask.put(count, stats);
            }
            group.put("perTask", perTask);

            groups.put(key, group);
        }
        return groups;
    }

    /**
     * @return min, max, mean and sum of the values, all 0 if there are none.
     */
    private static JsonObject stats(List<Long> values){
        long sum = values.stream().mapToLong(Long::longValue).sum();
        return new JsonObject()
                .put("min", values.stream().mapToLong(Long::longValue).min().orElse(0))
                .put("max", values.stream().mapToLong(Long::longValue).max().orElse(0))
                .put("mean", values.isEmpty()? 0.0 : (double) sum / values.size())
                .put("sum", sum);
    }

    private static List<JsonObject> readTaskUsage(Path experimentFolder){
        List<JsonObject> tasks = new ArrayList<>();
        if(!Files.isDirectory(experimentFolder)){
            return tasks;
        }

        try(Stream<Path> files = Files.list(experimentFolder)){
            files.filter(Files::isRegularFile)
                    .filter(file->file.getFileName().toString().endsWith(TASK_FILE_SUFFIX))
                    .sorted()
                    .forEach(file->{
                        try{
                            JsonObject task = new JsonObject(Files.readString(file));
                            if(task.getValue("totalTokens") instanceof Number && task.containsKey("evalId")){
                                tasks.add(task);
                            }else{
                                log.warn("{} is not a task token usage file, skipping it.", file);
                            }
                        }catch (IOException | RuntimeException e){
                            log.warn("Could not read task token usage from {}: {}", file, e.getMessage());
                        }
                    });
        }catch (IOException e){
            log.error("Could not list the experiment folder {}: {}", experimentFolder, e.getMessage());
        }
        return tasks;
    }
}
