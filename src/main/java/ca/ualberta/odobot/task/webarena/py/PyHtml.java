package ca.ualberta.odobot.task.webarena.py;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A port of CPython's {@code html.unescape}, which WebArena's {@code HTMLContentEvaluator} applies to every value it
 * reads off a page. The entity tables are CPython's own, from {@code webarena/py-html.json}
 * (see scripts/webarena-parity/gen_resources.py).
 */
public final class PyHtml {

    private PyHtml(){}

    private static final Pattern CHARREF = Pattern.compile(
            "&(#[0-9]+;?|#[xX][0-9a-fA-F]+;?|[^\\t\\n\\f <&#;]{1,32};?)");

    private static final Map<String, String> HTML5 = new HashMap<>();
    private static final Map<Integer, String> INVALID_CHARREFS = new HashMap<>();
    private static final Set<Integer> INVALID_CODEPOINTS = new HashSet<>();

    static {
        try(InputStream in = PyHtml.class.getResourceAsStream("/webarena/py-html.json")){
            if(in == null){
                throw new IllegalStateException("webarena/py-html.json is missing from the classpath");
            }
            JsonObject data = new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            data.getJsonObject("html5").forEach(e->HTML5.put(e.getKey(), (String) e.getValue()));
            data.getJsonObject("invalidCharrefs").forEach(e->INVALID_CHARREFS.put(Integer.parseInt(e.getKey()), (String) e.getValue()));
            JsonArray codepoints = data.getJsonArray("invalidCodepoints");
            for(int i = 0; i < codepoints.size(); i++){
                INVALID_CODEPOINTS.add(codepoints.getInteger(i));
            }
        }catch (IOException e){
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Python's {@code html.unescape(s)}.
     */
    public static String unescape(String s){
        if(s.indexOf('&') < 0){
            return s;
        }
        Matcher m = CHARREF.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        int last = 0;
        while(m.find()){
            out.append(s, last, m.start());
            out.append(replaceCharref(m.group(1)));
            last = m.end();
        }
        out.append(s, last, s.length());
        return out.toString();
    }

    private static String replaceCharref(String s){
        if(s.charAt(0) == '#'){
            BigInteger num;
            if(s.charAt(1) == 'x' || s.charAt(1) == 'X'){
                num = new BigInteger(PyStrings.rstrip(s.substring(2), ";"), 16);
            }else{
                num = new BigInteger(PyStrings.rstrip(s.substring(1), ";"));
            }
            if(num.compareTo(BigInteger.valueOf(0x10FFFF)) > 0){
                return "�";
            }
            int n = num.intValue();
            if(INVALID_CHARREFS.containsKey(n)){
                return INVALID_CHARREFS.get(n);
            }
            if(n >= 0xD800 && n <= 0xDFFF){
                return "�";
            }
            if(INVALID_CODEPOINTS.contains(n)){
                return "";
            }
            return new String(Character.toChars(n));
        }
        String named = HTML5.get(s);
        if(named != null){
            return named;
        }
        //The longest matching name, as the standard defines it: CPython tries prefixes of len(s)-1 down to 2 characters.
        int[] cps = s.codePoints().toArray();
        for(int x = cps.length - 1; x > 1; x--){
            String prefix = new String(cps, 0, x);
            String value = HTML5.get(prefix);
            if(value != null){
                return value + new String(cps, x, cps.length - x);
            }
        }
        return "&" + s;
    }
}
