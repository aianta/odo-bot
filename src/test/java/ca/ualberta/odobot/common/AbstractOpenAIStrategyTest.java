package ca.ualberta.odobot.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Services get the bare answer, whether or not the server returned its reasoning inside it.
 */
class AbstractOpenAIStrategyTest {

    @Test
    void reasoningInsideTheAnswerIsRemoved() {
        assertEquals("1", AbstractOpenAIStrategy.stripReasoning("<think>the first task matches</think>\n\n1"));
        assertEquals("1", AbstractOpenAIStrategy.stripReasoning("1\n<think>never closed"));
    }

    @Test
    void blankLinesLeftByReasoningReturnedSeparatelyAreRemoved() {
        //OpenRouter returns Qwen's reasoning in its own field; the answer keeps the blank lines that followed it.
        assertEquals("1", AbstractOpenAIStrategy.stripReasoning("\n\n1"));
        assertTrue(AIOutputValidators.isNumber.test(AbstractOpenAIStrategy.stripReasoning("\n\n1")));
    }

    @Test
    void anAnswerWithoutReasoningIsKept() {
        assertEquals("[1, 2]", AbstractOpenAIStrategy.stripReasoning("[1, 2]"));
    }
}
