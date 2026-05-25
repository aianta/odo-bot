package ca.ualberta.odobot.guidance;

public class TokenUsageRecord {

    public TokenUsageRecord merge(TokenUsageRecord other){
        this.inputTokens += other.inputTokens;
        this.outputTokens += other.outputTokens;
        this.totalTokens += other.totalTokens;
        return this;
    }

    public int inputTokens = 0;
    public int outputTokens = 0;
    public int totalTokens = 0;

    public void addInputTokens(int count){
        inputTokens += count;
    }

    public void addOutputTokens(int count){
        outputTokens += count;
    }

    public void addTotalTokens(int count){
        totalTokens += count;
    }

    public int computeTotalTokens(){
        return inputTokens + outputTokens;
    }
}
