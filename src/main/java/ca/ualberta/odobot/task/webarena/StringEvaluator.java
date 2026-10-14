package ca.ualberta.odobot.task.webarena;

import ca.ualberta.odobot.task.webarena.py.NltkWordTokenizer;
import ca.ualberta.odobot.task.webarena.py.PyStrings;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * WebArena's {@code StringEvaluator} ({@code string_match}): the agent's answer against the config's
 * {@code reference_answers}, by exact match, must-include, or the LLM judge.
 */
public class StringEvaluator implements WebArenaEvaluator {

    @Override
    public String evalType() {
        return "string_match";
    }

    public static double exactMatch(String ref, String pred){
        return PyStrings.cleanAnswer(pred).equals(PyStrings.cleanAnswer(ref))? 1.0 : 0.0;
    }

    /**
     * @param tokenize when the reference is a single character, look for it among the prediction's NLTK tokens rather
     *                 than as a substring, e.g. so that "0" isn't found in "10".
     */
    public static double mustInclude(String ref, String pred, boolean tokenize){
        String cleanRef = PyStrings.cleanAnswer(ref);
        String cleanPred = PyStrings.cleanAnswer(pred);
        if(tokenize && PyStrings.len(cleanRef) == 1 && NltkWordTokenizer.wordTokenize(cleanRef).size() == 1){
            return NltkWordTokenizer.wordTokenize(cleanPred).contains(cleanRef)? 1.0 : 0.0;
        }
        return cleanPred.contains(cleanRef)? 1.0 : 0.0;
    }

    @Override
    public double evaluate(WebArenaEndState end, JsonObject trace) throws Exception {
        JsonObject eval = end.eval();
        String pred = PyStrings.cleanAnswer(end.answer());
        trace.put("pred", pred);
        JsonArray steps = new JsonArray();
        trace.put("steps", steps);

        double score = 1.0;
        for(Map.Entry<String, Object> entry: eval.getJsonObject("reference_answers")){
            String approach = entry.getKey();
            Object value = entry.getValue();
            switch (approach){
                case "exact_match" -> {
                    double s = exactMatch((String) value, pred);
                    score *= s;
                    steps.add(new JsonObject().put("approach", approach).put("ref", value).put("score", s));
                }
                case "must_include" -> {
                    JsonArray refs = asList(value, approach);
                    for(Object mustValue: refs){
                        double s = mustInclude((String) mustValue, pred, refs.size() == 1);
                        score *= s;
                        steps.add(new JsonObject().put("approach", approach).put("ref", mustValue)
                                .put("tokenize", refs.size() == 1).put("score", s));
                    }
                }
                case "fuzzy_match" -> {
                    String intent = end.config().getString("intent");
                    if("N/A".equals(value)){
                        //The answer is "N/A" alone, or else the judge compares the reason the agent gave.
                        double s = exactMatch((String) value, pred);
                        score *= s;
                        steps.add(new JsonObject().put("approach", "fuzzy_match:N/A exact").put("ref", value).put("score", s));
                        if(score != 1){
                            //The original replaces the score here rather than multiplying it.
                            score = 1.0 * judge(end, trace, "ua_match",
                                    WebArenaJudge.uaMatchMessages(pred, eval.getString("string_note"), intent));
                            steps.add(new JsonObject().put("approach", "fuzzy_match:N/A ua_match")
                                    .put("ref", eval.getString("string_note")).put("score", score));
                        }
                    }else{
                        for(Object reference: asList(value, approach)){
                            double s = judge(end, trace, "fuzzy_match",
                                    WebArenaJudge.fuzzyMatchMessages(pred, (String) reference, intent));
                            score *= s;
                            steps.add(new JsonObject().put("approach", approach).put("ref", reference).put("score", s));
                        }
                    }
                }
                default -> {
                    //The original's match statement has no default case, so unknown approaches are ignored.
                    steps.add(new JsonObject().put("approach", approach).put("ignored", true));
                }
            }
        }
        return score;
    }

    private static JsonArray asList(Object value, String approach){
        if(!(value instanceof JsonArray array)){
            throw new IllegalArgumentException("'%s' must be a list (the original asserts it)".formatted(approach));
        }
        return array;
    }

    private static double judge(WebArenaEndState end, JsonObject trace, String kind, List<WebArenaJudge.Message> messages) throws Exception {
        if(end.judge() == null){
            throw new IllegalStateException("The task needs the LLM judge (%s), but 'webarenaEvaluation.judge' is not configured.".formatted(kind));
        }
        JsonArray calls = trace.getJsonArray("judgeCalls");
        if(calls == null){
            calls = new JsonArray();
            trace.put("judgeCalls", calls);
        }
        JsonObject call = new JsonObject().put("kind", kind)
                .put("messages", new JsonArray(messages.stream().map(m->new JsonObject().put("role", m.role()).put("content", m.content())).toList()));
        calls.add(call);
        String reply = end.judge().complete(messages);
        call.put("reply", reply);
        double score = kind.equals("ua_match")? WebArenaJudge.parseUaMatch(reply) : WebArenaJudge.parseFuzzyMatch(reply);
        call.put("score", score);
        return score;
    }
}
