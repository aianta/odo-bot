package ca.ualberta.odobot.task.webarena;

import java.util.List;

/**
 * The LLM judge of WebArena's {@code StringEvaluator} ({@code llm_fuzzy_match} and {@code llm_ua_match} in
 * {@code helper_functions.py}). The prompts and the reply parsing are fixed here, byte for byte as in the original;
 * implementations only send the messages.
 */
public interface WebArenaJudge {

    /**
     * One chat message, as the original sends it: role "system" or "user".
     */
    record Message(String role, String content){}

    /**
     * Send the messages with WebArena's sampling settings (temperature 0, top_p 1, 768 max tokens) and return the
     * reply's text.
     */
    String complete(List<Message> messages) throws Exception;

    /**
     * @return the messages of {@code llm_fuzzy_match(pred, reference, question)}.
     */
    static List<Message> fuzzyMatchMessages(String pred, String reference, String question){
        String message = "Help a teacher to grade the answer of a student given a question. Keep in mind that the student may use different phrasing or wording to answer the question. The goal is to evaluate whether the answer is semantically equivalent to the reference answer.\n";
        message += "question: " + question + "\n";
        message += "reference answer: " + reference + "\n";
        message += "all the string 'N/A' that you see is a special sequence that means 'not achievable'\n";
        message += "student answer: " + pred + "\n";
        message += "Conclude the judgement by correct/incorrect/partially correct.";
        return List.of(new Message("system", "You are a helpful assistant"), new Message("user", message));
    }

    /**
     * @return the messages of {@code llm_ua_match(pred, reference, question)}.
     */
    static List<Message> uaMatchMessages(String pred, String reference, String question){
        String message = "";
        message += "task: " + question + "\n";
        message += "actual unachievable reason: " + reference + "\n";
        message += "reported unachievable reason: " + pred + "\n";
        message += "The task described above is inherently unachievable due to the reason specified under 'actual unachievable reason'. "
                + "An individual previously attempted this task and was unable to complete it. They provided a reason for their failure, "
                + "which is listed under 'reported unachievable reason'. Your role is to review both the actual and reported reasons. "
                + "Determine if the reported reason aligns with the actual reason, even if implicitly. "
                + "If the stated reason is in line with the actual reason, respond with 'same'. Otherwise, respond with 'different'.";
        return List.of(new Message("system", "You are a helpful assistant"), new Message("user", message));
    }

    /**
     * The original's parsing of a fuzzy-match reply, lowercased: "partially correct" or "incorrect" scores 0, otherwise
     * the reply must contain "correct".
     *
     * @throws IllegalStateException where the original's {@code assert "correct" in response} fails.
     */
    static double parseFuzzyMatch(String reply){
        String response = reply.toLowerCase(java.util.Locale.ROOT);
        if(response.contains("partially correct") || response.contains("incorrect")){
            return 0.0;
        }
        if(!response.contains("correct")){
            throw new IllegalStateException("The judge's reply says neither correct nor incorrect: " + reply);
        }
        return 1.0;
    }

    /**
     * The original's parsing of an unachievable-reason reply: "different" scores 0, otherwise it must contain "same".
     */
    static double parseUaMatch(String reply){
        String response = reply.toLowerCase(java.util.Locale.ROOT);
        if(response.contains("different")){
            return 0.0;
        }
        if(!response.contains("same")){
            throw new IllegalStateException("The judge's reply says neither same nor different: " + reply);
        }
        return 1.0;
    }
}
