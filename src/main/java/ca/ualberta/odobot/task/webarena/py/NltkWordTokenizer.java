package ca.ualberta.odobot.task.webarena.py;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static ca.ualberta.odobot.task.webarena.py.PyRegex.*;

/**
 * A port of NLTK 3.10's {@code word_tokenize}: punkt sentence splitting ({@link PunktSentenceTokenizer}), then
 * {@code NLTKWordTokenizer} on each sentence. WebArena's {@code StringEvaluator.must_include} uses it when the
 * reference is a single character, e.g. "2", so that "12" doesn't count as containing it.
 *
 * <p>The regular expressions are those of {@code nltk/tokenize/destructive.py}, in the same order.</p>
 */
public final class NltkWordTokenizer {

    private NltkWordTokenizer(){}

    private record Rule(Pattern pattern, String replacement){
        String apply(String text){
            return sub(pattern, replacement, text);
        }
    }

    private static Rule rule(String regex, String replacement){
        return new Rule(compile(regex), replacement);
    }

    private static final List<Rule> STARTING_QUOTES = List.of(
            rule("([«“‘„]|[`]+)", " $1 "),
            rule("^\"", "``"),
            rule("(``)", " $1 "),
            rule("([ \\(\\[{<])(\"|'{2})", "$1 `` "),
            rule("(?i)(?<!" + W + ")(')(?!(?:re|ve|ll|m|t|s|d|n)" + B + ")(?=" + W + ")", "$1 ")
    );

    private static final List<Rule> ENDING_QUOTES = List.of(
            rule("([»”’])", " $1 "),
            rule("''", " '' "),
            rule("\"", " '' "),
            rule(S + "+", " "),
            rule("([^' ])('[sS]|'[mM]|'[dD]|') ", "$1 $2 "),
            rule("([^' ])('ll|'LL|'re|'RE|'ve|'VE|n't|N'T) ", "$1 $2 ")
    );

    private static final List<Rule> PUNCTUATION = List.of(
            rule("([^\\.])(\\.)([\\]\\)}>\"'»”’ ]*)" + S + "*$", "$1 $2 $3 "),
            rule("([:,])([^\\d])", " $1 $2"),
            rule("([:,])$", " $1 "),
            rule("\\.{2,}", " $0 "),
            rule("[;@#$%&]", " $0 "),
            rule("[\\u2012-\\u2015]", " $0 "),
            rule("([^\\.])(\\.)([\\]\\)}>\"']*)" + S + "*$", "$1 $2$3 "),
            rule("[?!]", " $0 "),
            rule("([^'])' ", "$1 ' "),
            rule("[*]", " $0 ")
    );

    private static final Rule PARENS_BRACKETS = rule("[\\]\\[\\(\\)\\{\\}\\<\\>]", " $0 ");

    private static final Rule DOUBLE_DASHES = rule("--", " -- ");

    private static final List<Rule> CONTRACTIONS2 = List.of(
            rule("(?i)" + B + "(can)(not)" + B, " $1 $2 "),
            rule("(?i)" + B + "(d)('ye)" + B, " $1 $2 "),
            rule("(?i)" + B + "(gim)(me)" + B, " $1 $2 "),
            rule("(?i)" + B + "(gon)(na)" + B, " $1 $2 "),
            rule("(?i)" + B + "(got)(ta)" + B, " $1 $2 "),
            rule("(?i)" + B + "(lem)(me)" + B, " $1 $2 "),
            rule("(?i)" + B + "(more)('n)" + B, " $1 $2 "),
            rule("(?i)" + B + "(wan)(na)(?=" + S + ")", " $1 $2 ")
    );

    private static final List<Rule> CONTRACTIONS3 = List.of(
            rule("(?i) ('t)(is)" + B, " $1 $2 "),
            rule("(?i) ('t)(was)" + B, " $1 $2 ")
    );

    /**
     * NLTK's {@code word_tokenize(text)}, for English.
     */
    public static List<String> wordTokenize(String text){
        List<String> tokens = new ArrayList<>();
        for(String sentence: PunktSentenceTokenizer.english().tokenize(text)){
            tokens.addAll(tokenize(sentence));
        }
        return tokens;
    }

    /**
     * NLTK's {@code NLTKWordTokenizer().tokenize(text)}, without parenthesis conversion.
     */
    public static List<String> tokenize(String text){
        for(Rule r: STARTING_QUOTES) text = r.apply(text);
        for(Rule r: PUNCTUATION) text = r.apply(text);
        text = PARENS_BRACKETS.apply(text);
        text = DOUBLE_DASHES.apply(text);
        text = " " + text + " ";
        for(Rule r: ENDING_QUOTES) text = r.apply(text);
        for(Rule r: CONTRACTIONS2) text = r.apply(text);
        for(Rule r: CONTRACTIONS3) text = r.apply(text);
        return PyStrings.split(text);
    }
}
