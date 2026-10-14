package ca.ualberta.odobot.task.webarena.py;

import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Python's {@code str()} gives for the value of a Playwright {@code page.evaluate(...)}, which WebArena's
 * {@code HTMLContentEvaluator} does to every JS locator's result.
 *
 * <p>The page side serializes the result the way Playwright 1.32 does in the page ({@code utilityScriptSerializers}),
 * see {@link #PLAYWRIGHT_SERIALIZER_JS}, and returns it as JSON text. {@link #fromPlaywright} then rebuilds the value
 * Playwright's Python client ({@code _js_handle.parse_value}) would return. JSON keeps the distinction Python sees
 * between ints and floats: a number written without a fraction or exponent is an int.</p>
 *
 * <p>Python values are modelled as: null (None), Boolean, BigInteger (int), Double (float), String (str),
 * List (list), LinkedHashMap with String keys (dict), {@link PyDatetime} and {@link PyUrl.ParseResult}.</p>
 */
public final class PyRepr {

    private PyRepr(){}

    /**
     * Playwright 1.32's in-page serializer ({@code serialize}/{@code innerSerialize} with a fall-through handle
     * serializer), as a JS function expression taking the value.
     */
    public static final String PLAYWRIGHT_SERIALIZER_JS = """
            (function(){
              function isRegExp(o){ try { return o instanceof RegExp || Object.prototype.toString.call(o) === '[object RegExp]'; } catch (e) { return false; } }
              function isDate(o){ try { return o instanceof Date || Object.prototype.toString.call(o) === '[object Date]'; } catch (e) { return false; } }
              function isURL(o){ try { return o instanceof URL || Object.prototype.toString.call(o) === '[object URL]'; } catch (e) { return false; } }
              function isError(o){ try { var p; return o instanceof Error || o && ((p = Object.getPrototypeOf(o)) === null || p === void 0 ? void 0 : p.name) === 'Error'; } catch (e) { return false; } }
              function serialize(value, info){
                if (value && typeof value === 'object') {
                  if (typeof globalThis.Window === 'function' && value instanceof globalThis.Window) return 'ref: <Window>';
                  if (typeof globalThis.Document === 'function' && value instanceof globalThis.Document) return 'ref: <Document>';
                  if (typeof globalThis.Node === 'function' && value instanceof globalThis.Node) return 'ref: <Node>';
                }
                return inner(value, info);
              }
              function inner(value, info){
                if (typeof value === 'symbol') return {v: 'undefined'};
                if (Object.is(value, undefined)) return {v: 'undefined'};
                if (Object.is(value, null)) return {v: 'null'};
                if (Object.is(value, NaN)) return {v: 'NaN'};
                if (Object.is(value, Infinity)) return {v: 'Infinity'};
                if (Object.is(value, -Infinity)) return {v: '-Infinity'};
                if (Object.is(value, -0)) return {v: '-0'};
                if (typeof value === 'boolean') return value;
                if (typeof value === 'number') return value;
                if (typeof value === 'string') return value;
                if (isError(value)) {
                  if ('captureStackTrace' in globalThis.Error) return value.stack || '';
                  return `${value.name}: ${value.message}\\n${value.stack}`;
                }
                if (isDate(value)) return {d: value.toJSON()};
                if (isURL(value)) return {u: value.toJSON()};
                if (isRegExp(value)) return {r: {p: value.source, f: value.flags}};
                const seen = info.visited.get(value);
                if (seen) return {ref: seen};
                if (Array.isArray(value)) {
                  const a = [];
                  const id = ++info.lastId;
                  info.visited.set(value, id);
                  for (let i = 0; i < value.length; ++i) a.push(serialize(value[i], info));
                  return {a, id};
                }
                if (typeof value === 'object') {
                  const o = [];
                  const id = ++info.lastId;
                  info.visited.set(value, id);
                  for (const name of Object.keys(value)) {
                    let item;
                    try { item = value[name]; } catch (e) { continue; }
                    if (name === 'toJSON' && typeof item === 'function') o.push({k: name, v: {o: [], id: 0}});
                    else o.push({k: name, v: serialize(item, info)});
                  }
                  if (o.length === 0 && value.toJSON && typeof value.toJSON === 'function') return inner(value.toJSON(), info);
                  return {o, id};
                }
              }
              return function(value){ return serialize(value, {visited: new Map(), lastId: 0}); };
            })()""";

    /**
     * Python's {@code datetime.datetime}, as Playwright returns a JS Date.
     */
    public record PyDatetime(LocalDateTime value){}

    /**
     * Rebuild the Python value Playwright's Python client returns, from the JSON text of the page-side serialization.
     * An undefined result (Playwright sends nothing) is None.
     */
    public static Object fromPlaywright(String json){
        if(json == null){
            return null;
        }
        return parse(Json.decodeValue(json), new java.util.HashMap<>());
    }

    private static Object parse(Object value, Map<Integer, Object> refs){
        if(value == null){
            return null;
        }
        if(value instanceof JsonObject o){
            if(o.containsKey("ref")){
                return refs.get(o.getInteger("ref"));
            }
            if(o.containsKey("v")){
                String v = String.valueOf(o.getValue("v"));
                return switch (v){
                    case "Infinity" -> Double.POSITIVE_INFINITY;
                    case "-Infinity" -> Double.NEGATIVE_INFINITY;
                    case "-0" -> -0.0d;
                    case "NaN" -> Double.NaN;
                    case "undefined", "null" -> null;
                    default -> v;
                };
            }
            if(o.containsKey("u")){
                return PyUrl.urlparse(o.getString("u"));
            }
            if(o.containsKey("a")){
                List<Object> list = new ArrayList<>();
                refs.put(o.getInteger("id"), list);
                for(Object e: o.getJsonArray("a")){
                    list.add(parse(e, refs));
                }
                return list;
            }
            if(o.containsKey("d")){
                String d = o.getString("d");
                return new PyDatetime(LocalDateTime.parse(d.substring(0, d.length() - 1)));
            }
            if(o.containsKey("o")){
                Map<String, Object> dict = new LinkedHashMap<>();
                refs.put(o.getInteger("id"), dict);
                for(Object e: o.getJsonArray("o")){
                    JsonObject entry = (JsonObject) e;
                    dict.put(entry.getString("k"), parse(entry.getValue("v"), refs));
                }
                return dict;
            }
            //Python's parse_value returns any other dict, such as a RegExp's {"r": ...}, as it is.
            return toPlain(o);
        }
        if(value instanceof JsonArray a){
            return toPlain(a);
        }
        if(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte){
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if(value instanceof BigInteger || value instanceof Double || value instanceof String || value instanceof Boolean){
            return value;
        }
        if(value instanceof Float f){
            return f.doubleValue();
        }
        if(value instanceof BigDecimal bd){
            return bd.doubleValue();
        }
        return value;
    }

    /**
     * The Python value of a JSON value, as Python's {@code json.loads} gives it: no Playwright encoding.
     */
    public static Object fromJson(Object value){
        return toPlain(value);
    }

    private static Object toPlain(Object value){
        if(value instanceof JsonObject o){
            Map<String, Object> dict = new LinkedHashMap<>();
            o.forEach(e->dict.put(e.getKey(), toPlain(e.getValue())));
            return dict;
        }
        if(value instanceof JsonArray a){
            List<Object> list = new ArrayList<>();
            a.forEach(e->list.add(toPlain(e)));
            return list;
        }
        return parse(value, Map.of());
    }

    /**
     * Python's {@code str(value)}.
     */
    public static String str(Object value){
        if(value instanceof String s){
            return s;
        }
        return repr(value);
    }

    /**
     * Python's {@code repr(value)}.
     */
    public static String repr(Object value){
        StringBuilder out = new StringBuilder();
        repr(value, out, Collections.newSetFromMap(new IdentityHashMap<>()));
        return out.toString();
    }

    private static void repr(Object value, StringBuilder out, Set<Object> inProgress){
        if(value == null){
            out.append("None");
        }else if(value instanceof Boolean b){
            out.append(b? "True" : "False");
        }else if(value instanceof BigInteger || value instanceof Integer || value instanceof Long){
            out.append(value);
        }else if(value instanceof Double d){
            out.append(reprFloat(d));
        }else if(value instanceof String s){
            out.append(reprString(s));
        }else if(value instanceof List<?> list){
            if(!inProgress.add(list)){
                out.append("[...]");
                return;
            }
            out.append('[');
            for(int i = 0; i < list.size(); i++){
                if(i > 0) out.append(", ");
                repr(list.get(i), out, inProgress);
            }
            out.append(']');
            inProgress.remove(list);
        }else if(value instanceof Map<?, ?> map){
            if(!inProgress.add(map)){
                out.append("{...}");
                return;
            }
            out.append('{');
            boolean first = true;
            for(Map.Entry<?, ?> e: map.entrySet()){
                if(!first) out.append(", ");
                first = false;
                repr(e.getKey(), out, inProgress);
                out.append(": ");
                repr(e.getValue(), out, inProgress);
            }
            out.append('}');
            inProgress.remove(map);
        }else if(value instanceof PyDatetime dt){
            //str(datetime) is isoformat(sep=' '); repr would be datetime.datetime(...), but WebArena only ever calls str().
            LocalDateTime t = dt.value();
            out.append("%04d-%02d-%02d %02d:%02d:%02d".formatted(t.getYear(), t.getMonthValue(), t.getDayOfMonth(),
                    t.getHour(), t.getMinute(), t.getSecond()));
            int micros = t.getNano() / 1000;
            if(micros != 0){
                out.append(".%06d".formatted(micros));
            }
        }else if(value instanceof PyUrl.ParseResult p){
            out.append(p.pyRepr());
        }else{
            throw new IllegalArgumentException("Not a Python value: " + value.getClass());
        }
    }

    /**
     * Python's {@code repr(float)}: the shortest digits that round-trip, in fixed notation unless the decimal exponent
     * is below -4 or above 16, with at least one digit after the point.
     */
    public static String reprFloat(double d){
        if(Double.isNaN(d)) return "nan";
        if(Double.isInfinite(d)) return d > 0? "inf" : "-inf";
        if(d == 0) return (1 / d < 0)? "-0.0" : "0.0";

        String sign = d < 0? "-" : "";
        BigDecimal bd = shortestRoundTrip(Math.abs(d));
        String digits = bd.unscaledValue().toString();
        int decpt = digits.length() - bd.scale(); //value = 0.<digits> * 10^decpt

        if(decpt <= -4 || decpt > 16){
            StringBuilder s = new StringBuilder(sign).append(digits.charAt(0));
            if(digits.length() > 1){
                s.append('.').append(digits, 1, digits.length());
            }
            int exp = decpt - 1;
            s.append('e').append(exp < 0? '-' : '+');
            String e = String.valueOf(Math.abs(exp));
            if(e.length() < 2) s.append('0');
            return s.append(e).toString();
        }
        if(decpt <= 0){
            return sign + "0." + "0".repeat(-decpt) + digits;
        }
        if(decpt >= digits.length()){
            return sign + digits + "0".repeat(decpt - digits.length()) + ".0";
        }
        return sign + digits.substring(0, decpt) + "." + digits.substring(decpt);
    }

    /**
     * The decimal with the fewest significant digits that parses back to {@code d}, and of those the closest to
     * {@code d}, as Python's repr picks. {@link Double#toString} is not enough: it sometimes keeps a second digit,
     * e.g. 4.9E-324 where Python prints 5e-324.
     */
    private static BigDecimal shortestRoundTrip(double d){
        BigDecimal exact = new BigDecimal(d);
        for(int n = 1; n <= 17; n++){
            BigDecimal nearest = exact.round(new java.math.MathContext(n, java.math.RoundingMode.HALF_EVEN));
            BigDecimal unit = nearest.ulp();
            BigDecimal best = null;
            //Next to a power of two the round-trip interval is lopsided, so a neighbour can round-trip where the nearest doesn't.
            for(BigDecimal candidate: new BigDecimal[]{nearest, nearest.add(unit), nearest.subtract(unit)}){
                if(candidate.signum() > 0 && candidate.round(new java.math.MathContext(n)).compareTo(candidate) == 0
                        && Double.parseDouble(candidate.toString()) == d){
                    if(best == null || candidate.subtract(exact).abs().compareTo(best.subtract(exact).abs()) < 0){
                        best = candidate;
                    }
                }
            }
            if(best != null){
                return best.stripTrailingZeros();
            }
        }
        return new BigDecimal(Double.toString(d)).stripTrailingZeros();
    }

    /**
     * Python's {@code repr(str)}: single quotes unless the string has a single quote and no double quote; backslash,
     * the quote, tab, newline and carriage return escaped; other non-printable characters as hex escapes.
     */
    public static String reprString(String s){
        char quote = (s.indexOf('\'') >= 0 && s.indexOf('"') < 0)? '"' : '\'';
        StringBuilder out = new StringBuilder(s.length() + 2).append(quote);
        s.codePoints().forEach(cp->{
            if(cp == quote || cp == '\\'){
                out.append('\\').appendCodePoint(cp);
            }else if(cp == '\t'){
                out.append("\\t");
            }else if(cp == '\n'){
                out.append("\\n");
            }else if(cp == '\r'){
                out.append("\\r");
            }else if(cp < ' ' || cp == 0x7F){
                out.append("\\x%02x".formatted(cp));
            }else if(cp < 0x7F || isPrintable(cp)){
                out.appendCodePoint(cp);
            }else if(cp <= 0xFF){
                out.append("\\x%02x".formatted(cp));
            }else if(cp <= 0xFFFF){
                out.append("\\u%04x".formatted(cp));
            }else{
                out.append("\\U%08x".formatted(cp));
            }
        });
        return out.append(quote).toString();
    }

    /**
     * Python's {@code str.isprintable()} for one character: not in the categories Cc, Cf, Cs, Co, Cn, Zl, Zp, and not
     * a space separator other than ' '.
     */
    static boolean isPrintable(int cp){
        if(cp == ' ') return true;
        return switch (Character.getType(cp)){
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
                 Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }
}
