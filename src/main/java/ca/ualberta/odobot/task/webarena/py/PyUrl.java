package ca.ualberta.odobot.task.webarena.py;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ports of the {@code urllib.parse} functions WebArena's {@code URLEvaluator} and {@code reddit_get_post_url} use,
 * following CPython 3.10.21, the Python of the WebArena environment the parity tests compare against.
 */
public final class PyUrl {

    private PyUrl(){}

    /**
     * Python's {@code urllib.parse.ParseResult}.
     */
    public record ParseResult(String scheme, String netloc, String path, String params, String query, String fragment){

        /**
         * @return Python's {@code repr()} of the result, which {@code str()} also gives.
         */
        public String pyRepr(){
            return "ParseResult(scheme=%s, netloc=%s, path=%s, params=%s, query=%s, fragment=%s)".formatted(
                    PyRepr.reprString(scheme), PyRepr.reprString(netloc), PyRepr.reprString(path),
                    PyRepr.reprString(params), PyRepr.reprString(query), PyRepr.reprString(fragment));
        }
    }

    private static final String SCHEME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+-.";

    private static final Set<String> USES_PARAMS = Set.of("", "ftp", "hdl", "prospero", "http", "imap", "https",
            "shttp", "rtsp", "rtspu", "sip", "sips", "mms", "sftp", "tel");

    /**
     * Python's {@code urllib.parse.urlparse(url)}.
     *
     * @throws IllegalArgumentException where Python raises ValueError (an unbalanced IPv6 bracket).
     */
    public static ParseResult urlparse(String url){
        String[] split = urlsplit(url);
        String scheme = split[0];
        String path = split[2];
        String params = "";
        if(USES_PARAMS.contains(scheme) && path.contains(";")){
            String[] p = splitParams(path);
            path = p[0];
            params = p[1];
        }
        return new ParseResult(scheme, split[1], path, params, split[3], split[4]);
    }

    /**
     * Python's {@code urllib.parse.urlsplit(url)}: scheme, netloc, path, query, fragment.
     */
    public static String[] urlsplit(String url){
        //Only lstrip url, as Python does.
        int start = 0;
        while(start < url.length() && url.charAt(start) <= ' '){
            start++;
        }
        url = url.substring(start).replace("\t", "").replace("\r", "").replace("\n", "");

        String scheme = "";
        String netloc = "";
        String query = "";
        String fragment = "";
        int i = url.indexOf(':');
        if(i > 0){
            boolean allSchemeChars = true;
            for(int k = 0; k < i; k++){
                if(SCHEME_CHARS.indexOf(url.charAt(k)) < 0){
                    allSchemeChars = false;
                    break;
                }
            }
            if(allSchemeChars){
                scheme = PyStrings.lower(url.substring(0, i));
                url = url.substring(i + 1);
            }
        }
        if(url.startsWith("//")){
            int delim = url.length();
            for(char c: new char[]{'/', '?', '#'}){
                int w = url.indexOf(c, 2);
                if(w >= 0){
                    delim = Math.min(delim, w);
                }
            }
            netloc = url.substring(2, delim);
            url = url.substring(delim);
            if((netloc.contains("[") && !netloc.contains("]")) || (netloc.contains("]") && !netloc.contains("["))){
                throw new IllegalArgumentException("Invalid IPv6 URL");
            }
        }
        int hash = url.indexOf('#');
        if(hash >= 0){
            fragment = url.substring(hash + 1);
            url = url.substring(0, hash);
        }
        int q = url.indexOf('?');
        if(q >= 0){
            query = url.substring(q + 1);
            url = url.substring(0, q);
        }
        return new String[]{scheme, netloc, url, query, fragment};
    }

    private static String[] splitParams(String url){
        int i;
        if(url.contains("/")){
            i = url.indexOf(';', url.lastIndexOf('/'));
            if(i < 0){
                return new String[]{url, ""};
            }
        }else{
            i = url.indexOf(';');
        }
        return new String[]{url.substring(0, i), url.substring(i + 1)};
    }

    /**
     * Python's {@code urllib.parse.parse_qs(qs)}: blank values are dropped, fields without '=' are skipped, '+' is a
     * space and %-escapes are decoded as UTF-8 with replacement. Keys keep their first-seen order.
     */
    public static Map<String, List<String>> parseQs(String qs){
        Map<String, List<String>> result = new LinkedHashMap<>();
        for(String nameValue: qs.split("&", -1)){
            if(nameValue.isEmpty()){
                continue;
            }
            int eq = nameValue.indexOf('=');
            if(eq < 0){
                continue;
            }
            String rawValue = nameValue.substring(eq + 1);
            if(rawValue.isEmpty()){
                continue;
            }
            String name = unquote(nameValue.substring(0, eq).replace('+', ' '));
            String value = unquote(rawValue.replace('+', ' '));
            result.computeIfAbsent(name, k->new ArrayList<>()).add(value);
        }
        return result;
    }

    /**
     * Python's {@code urllib.parse.unquote(s)}: %-escapes in each run of ASCII characters are decoded as UTF-8, with
     * U+FFFD for invalid sequences. Runs of non-ASCII characters are kept as they are.
     */
    public static String unquote(String s){
        if(s.indexOf('%') < 0){
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while(i < n){
            int start = i;
            if(s.charAt(i) < 0x80){
                while(i < n && s.charAt(i) < 0x80) i++;
                out.append(decodeUtf8(unquoteToBytes(s.substring(start, i))));
            }else{
                while(i < n && s.charAt(i) >= 0x80) i++;
                out.append(s, start, i);
            }
        }
        return out.toString();
    }

    private static byte[] unquoteToBytes(String ascii){
        ByteArrayOutputStream out = new ByteArrayOutputStream(ascii.length());
        String[] bits = ascii.split("%", -1);
        out.writeBytes(bits[0].getBytes(StandardCharsets.US_ASCII));
        for(int k = 1; k < bits.length; k++){
            String item = bits[k];
            if(item.length() >= 2 && isHex(item.charAt(0)) && isHex(item.charAt(1))){
                out.write(Integer.parseInt(item.substring(0, 2), 16));
                out.writeBytes(item.substring(2).getBytes(StandardCharsets.US_ASCII));
            }else{
                out.write('%');
                out.writeBytes(item.getBytes(StandardCharsets.US_ASCII));
            }
        }
        return out.toByteArray();
    }

    private static boolean isHex(char c){
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static String decodeUtf8(byte[] bytes){
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith("�");
        try{
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        }catch (CharacterCodingException e){
            //Cannot happen with REPLACE.
            throw new IllegalStateException(e);
        }
    }
}
