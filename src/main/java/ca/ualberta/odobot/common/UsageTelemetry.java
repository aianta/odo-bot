package ca.ualberta.odobot.common;

import java.util.function.Consumer;

public interface UsageTelemetry {

    void addPromptTokenConsumer(Consumer<Integer> promptTokenConsumer);

    void addCompletionTokenConsumer(Consumer<Integer> completionTokenConsumer);

    void addTotalTokenConsumer(Consumer<Integer> totalTokenConsumer);
}
