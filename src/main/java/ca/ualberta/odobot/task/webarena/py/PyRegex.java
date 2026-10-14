package ca.ualberta.odobot.task.webarena.py;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python {@code re} character classes and substitution, for porting Python regular expressions to Java.
 *
 * <p>Java's {@code \w}, {@code \s} and {@code \b} differ from Python's even with
 * {@link Pattern#UNICODE_CHARACTER_CLASS}: Java's {@code \w} also matches combining marks, and Java's {@code \s} leaves
 * out U+001C to U+001F, which Python's {@code str.isspace()} counts. Ported patterns use these constants instead.
 * {@link Pattern#UNIX_LINES} makes {@code $} and {@code .} treat only {@code \n} as a line end, as Python does.</p>
 */
public final class PyRegex {

    private PyRegex(){}

    public static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES;

    /** Python's {@code \w}: what {@code str.isalnum()} accepts, and '_'. */
    public static final String W = "[\\p{L}\\p{N}_]";
    /** Python's {@code \W}. */
    public static final String NOT_W = "[^\\p{L}\\p{N}_]";
    /** Python's {@code [^\W\d]}: word characters that are not decimal digits. */
    public static final String W_NOT_D = "[\\p{L}\\p{Nl}\\p{No}_]";
    /** Python's {@code \s}. */
    public static final String S = "[\\s\\x1c-\\x1f]";
    /** Python's {@code \S}. */
    public static final String NOT_S = "[^\\s\\x1c-\\x1f]";
    /** Python's {@code \b}. */
    public static final String B = "(?:(?<=" + W + ")(?!" + W + ")|(?<!" + W + ")(?=" + W + "))";

    public static Pattern compile(String regex){
        return Pattern.compile(regex, FLAGS);
    }

    public static Pattern compile(String regex, int extraFlags){
        return Pattern.compile(regex, FLAGS | extraFlags);
    }

    /**
     * Python's {@code pattern.sub(replacement, text)}, with the replacement written in Java syntax ({@code $1}). A group
     * that did not take part in the match is replaced by nothing, as in Python.
     */
    public static String sub(Pattern pattern, String replacement, String text){
        Matcher m = pattern.matcher(text);
        if(!m.find()){
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        do{
            m.appendReplacement(out, groupsOrEmpty(m, replacement));
        }while(m.find());
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Expand the replacement's group references by hand, so that groups that did not match give "" as in Python,
     * rather than "null".
     */
    private static String groupsOrEmpty(Matcher m, String replacement){
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < replacement.length(); i++){
            char c = replacement.charAt(i);
            if(c == '$' && i + 1 < replacement.length() && Character.isDigit(replacement.charAt(i + 1))){
                int group = replacement.charAt(i + 1) - '0';
                String value = m.group(group);
                out.append(value == null? "" : value);
                i++;
            }else{
                out.append(c);
            }
        }
        return Matcher.quoteReplacement(out.toString());
    }
}
