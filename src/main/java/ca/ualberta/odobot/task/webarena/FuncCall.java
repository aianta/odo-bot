package ca.ualberta.odobot.task.webarena;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code func:} call from a WebArena config, e.g. {@code reddit_get_post_url('http://...')} or
 * {@code gitlab_get_project_memeber_role(page, 'byteblaze')}. The original runs these with Python's {@code eval}; here
 * they are parsed, and only WebArena's helper functions can be called. Arguments are Python string literals or the
 * name {@code page}.
 */
public record FuncCall(String name, List<Object> args) {

    /**
     * Stands for the {@code page} argument.
     */
    public static final Object PAGE = new Object(){
        @Override
        public String toString() {
            return "page";
        }
    };

    private static final Pattern CALL = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*$", Pattern.DOTALL);

    /**
     * @param source the call, after WebArena's own substitutions ({@code __last_url__}, {@code __page__}).
     * @throws IllegalArgumentException where Python's {@code eval} would raise a SyntaxError.
     */
    public static FuncCall parse(String source){
        Matcher m = CALL.matcher(source);
        if(!m.matches()){
            throw new IllegalArgumentException("Not a function call: " + source);
        }
        return new FuncCall(m.group(1), parseArgs(m.group(2), source));
    }

    private static List<Object> parseArgs(String text, String source){
        List<Object> args = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while(true){
            while(i < n && Character.isWhitespace(text.charAt(i))) i++;
            if(i >= n){
                return args;
            }
            char c = text.charAt(i);
            if(c == '\'' || c == '"'){
                StringBuilder value = new StringBuilder();
                i++;
                boolean closed = false;
                while(i < n){
                    char ch = text.charAt(i);
                    if(ch == '\\' && i + 1 < n){
                        i = unescape(text, i, value);
                        continue;
                    }
                    if(ch == c){
                        closed = true;
                        i++;
                        break;
                    }
                    value.append(ch);
                    i++;
                }
                if(!closed){
                    throw new IllegalArgumentException("Unterminated string in " + source);
                }
                args.add(value.toString());
            }else if(text.startsWith("page", i) && (i + 4 == n || !Character.isJavaIdentifierPart(text.charAt(i + 4)))){
                args.add(PAGE);
                i += 4;
            }else{
                throw new IllegalArgumentException("Unsupported argument at '%s' in %s".formatted(text.substring(i), source));
            }
            while(i < n && Character.isWhitespace(text.charAt(i))) i++;
            if(i < n){
                if(text.charAt(i) != ','){
                    throw new IllegalArgumentException("Expected ',' at '%s' in %s".formatted(text.substring(i), source));
                }
                i++;
            }
        }
    }

    /**
     * Decode the Python escape sequence at {@code text[i]} (a backslash) into {@code out}.
     *
     * @return the index after the sequence.
     */
    private static int unescape(String text, int i, StringBuilder out){
        char e = text.charAt(i + 1);
        switch (e){
            case '\\' -> out.append('\\');
            case '\'' -> out.append('\'');
            case '"' -> out.append('"');
            case 'n' -> out.append('\n');
            case 't' -> out.append('\t');
            case 'r' -> out.append('\r');
            case '\n' -> {} //Line continuation.
            case 'x' -> {
                out.append((char) Integer.parseInt(text.substring(i + 2, i + 4), 16));
                return i + 4;
            }
            case 'u' -> {
                out.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                return i + 6;
            }
            //Python keeps unknown escapes, backslash included.
            default -> out.append('\\').append(e);
        }
        return i + 2;
    }

    public String stringArg(int index){
        if(index >= args.size() || !(args.get(index) instanceof String s)){
            throw new IllegalArgumentException("%s needs a string as argument %d".formatted(name, index + 1));
        }
        return s;
    }

    public void expectArgs(int count){
        if(args.size() != count){
            throw new IllegalArgumentException("%s() takes %d arguments, got %d".formatted(name, count, args.size()));
        }
    }
}
