package ca.ualberta.odobot.task;

/**
 * What a {@link TaskVerticle} reports to the orchestrator when its task is over. The details (token usage, timing)
 * are in the task's {@code <evalId>-tokens.json}, which is saved before the outcome is reported.
 *
 * @param outcome {@code completed}, {@code failed: <reason>}, or {@code failed: setup: <error>}.
 * @param harnessError what went wrong saving or scoring the task after it ran, or null.
 */
public record TaskOutcome(String evalId, String outcome, String harnessError) {
}
