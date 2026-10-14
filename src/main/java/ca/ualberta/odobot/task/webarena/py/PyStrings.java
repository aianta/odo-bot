package ca.ualberta.odobot.task.webarena.py;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Python {@code str} methods that WebArena's evaluators rely on, with Python's semantics: whitespace is what
 * {@code str.isspace()} accepts, and indexing is by code point.
 */
public final class PyStrings {

    private PyStrings(){}

    /**
     * @return true for the characters Python's {@code str.isspace()} accepts. Java's {@link Character#isWhitespace}
     * leaves out the no-break spaces and NEL, which Python counts.
     */
    public static boolean isSpace(int codePoint){
        return Character.isWhitespace(codePoint)
                || codePoint == 0x00A0 || codePoint == 0x2007 || codePoint == 0x202F || codePoint == 0x0085;
    }

    /**
     * Python's {@code str.strip()}.
     */
    public static String strip(String s){
        return rstrip(lstrip(s));
    }

    public static String lstrip(String s){
        int i = 0;
        while(i < s.length()){
            int cp = s.codePointAt(i);
            if(!isSpace(cp)) break;
            i += Character.charCount(cp);
        }
        return s.substring(i);
    }

    /**
     * Python's {@code str.rstrip()}.
     */
    public static String rstrip(String s){
        int end = s.length();
        while(end > 0){
            int cp = s.codePointBefore(end);
            if(!isSpace(cp)) break;
            end -= Character.charCount(cp);
        }
        return s.substring(0, end);
    }

    /**
     * Python's {@code str.rstrip(chars)}.
     */
    public static String rstrip(String s, String chars){
        int end = s.length();
        while(end > 0){
            int cp = s.codePointBefore(end);
            if(chars.indexOf(cp) < 0) break;
            end -= Character.charCount(cp);
        }
        return s.substring(0, end);
    }

    /**
     * Python's {@code str.split()} without arguments: split on runs of whitespace, dropping empty strings.
     */
    public static List<String> split(String s){
        List<String> parts = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while(i < n){
            while(i < n && isSpace(s.codePointAt(i))) i += Character.charCount(s.codePointAt(i));
            if(i >= n) break;
            int start = i;
            while(i < n && !isSpace(s.codePointAt(i))) i += Character.charCount(s.codePointAt(i));
            parts.add(s.substring(start, i));
        }
        return parts;
    }

    /**
     * Python's {@code str.lower()}.
     */
    public static String lower(String s){
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * @return the number of characters as Python counts them, i.e. code points.
     */
    public static int len(String s){
        return s.codePointCount(0, s.length());
    }

    /**
     * WebArena's {@code StringEvaluator.clean_answer}: strip, remove one pair of matching outer quotes, lowercase.
     */
    public static String cleanAnswer(String answer){
        answer = strip(answer);
        if((answer.startsWith("'") && answer.endsWith("'")) || (answer.startsWith("\"") && answer.endsWith("\""))){
            //Python's answer[1:-1] is empty for a lone quote, where substring(1, 0) would throw.
            answer = answer.length() < 2? "" : answer.substring(1, answer.length() - 1);
        }
        return lower(answer);
    }
}
