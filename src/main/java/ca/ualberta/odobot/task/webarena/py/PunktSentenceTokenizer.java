package ca.ualberta.odobot.task.webarena.py;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ca.ualberta.odobot.task.webarena.py.PyRegex.*;

/**
 * A port of the sentence splitting half of NLTK 3.10's {@code PunktSentenceTokenizer}, with NLTK's pretrained English
 * parameters ({@code punkt_tab/english}, copied to {@code webarena/punkt_tab/english}). Training is not ported.
 *
 * <p>Method names follow {@code nltk/tokenize/punkt.py}. Indexes are Java {@code char} indexes throughout, which is
 * consistent with Python's code point indexes since every index comes from a match or a search on the same string.</p>
 */
public final class PunktSentenceTokenizer {

    private static final int ORTHO_BEG_UC = 1 << 1;
    private static final int ORTHO_MID_UC = 1 << 2;
    private static final int ORTHO_UNK_UC = 1 << 3;
    private static final int ORTHO_BEG_LC = 1 << 4;
    private static final int ORTHO_MID_LC = 1 << 5;
    private static final int ORTHO_UNK_LC = 1 << 6;
    private static final int ORTHO_UC = ORTHO_BEG_UC + ORTHO_MID_UC + ORTHO_UNK_UC;
    private static final int ORTHO_LC = ORTHO_BEG_LC + ORTHO_MID_LC + ORTHO_UNK_LC;

    private static final Set<String> SENT_END_CHARS = Set.of(".", "?", "!");
    private static final Set<String> PUNCTUATION = Set.of(";", ":", ",", ".", "!", "?");

    //PunktLanguageVars, with re.escape("".join(set(sent_end_chars) - {"."})) = "?!".
    private static final String NON_WORD_CHARS = "(?:[)\";}\\]\\*:@'\\({\\[‘’“”«»?!])";
    private static final String MULTI_CHAR_PUNCT = "(?:\\-{2,}|\\.{2,}|(?:\\." + S + "){2,}\\.)";
    private static final String WORD_START = "[^\\(\"`{\\[:;&#\\*@\\)}\\]\\-,]";

    private static final Pattern WORD_TOKENIZER = compile(
            MULTI_CHAR_PUNCT
            + "|(?=" + WORD_START + ")" + NOT_S + "+?"
            + "(?=" + S + "|$|" + NON_WORD_CHARS + "|" + MULTI_CHAR_PUNCT
            + "|,(?=$|" + S + "|" + NON_WORD_CHARS + "|" + MULTI_CHAR_PUNCT + "))"
            + "|" + NOT_S);

    private static final Pattern PERIOD_CONTEXT = compile(
            "[\\.\\?!](?=(?<afterTok>" + NON_WORD_CHARS + "|" + S + "+(?<nextTok>" + NOT_S + "+)))");

    private static final Pattern BOUNDARY_REALIGNMENT = compile(
            "[\"')\\]}‘’“”«»]+?(?:" + S + "+|(?=--)|$)", Pattern.MULTILINE);

    private static final Pattern RE_ELLIPSIS = compile("\\.\\.+$");
    private static final Pattern RE_NUMERIC = compile("^-?[\\.,]?\\d[\\d,\\.-]*\\.?$");
    private static final Pattern RE_INITIAL = compile(W_NOT_D + "\\.$");
    private static final Pattern RE_NON_PUNCT = compile(W_NOT_D);

    private static volatile PunktSentenceTokenizer english;

    private final Set<String> abbrevTypes;
    private final Set<List<String>> collocations;
    private final Set<String> sentStarters;
    private final Map<String, Integer> orthoContext;

    private PunktSentenceTokenizer(Set<String> abbrevTypes, Set<List<String>> collocations, Set<String> sentStarters,
                                   Map<String, Integer> orthoContext){
        this.abbrevTypes = abbrevTypes;
        this.collocations = collocations;
        this.sentStarters = sentStarters;
        this.orthoContext = orthoContext;
    }

    /**
     * @return the tokenizer with NLTK's English parameters, as {@code nltk.tokenize.sent_tokenize} uses.
     */
    public static PunktSentenceTokenizer english(){
        PunktSentenceTokenizer t = english;
        if(t == null){
            synchronized (PunktSentenceTokenizer.class){
                if(english == null){
                    english = load("/webarena/punkt_tab/english/");
                }
                t = english;
            }
        }
        return t;
    }

    private static PunktSentenceTokenizer load(String dir){
        Set<String> abbrevs = new HashSet<>();
        Set<List<String>> collocations = new HashSet<>();
        Set<String> starters = new HashSet<>();
        Map<String, Integer> ortho = new HashMap<>();
        readLines(dir + "abbrev_types.txt", abbrevs::add);
        readLines(dir + "sent_starters.txt", starters::add);
        readLines(dir + "collocations.tab", line->collocations.add(List.of(line.split("\t", -1))));
        readLines(dir + "ortho_context.tab", line->{
            String[] parts = line.split("\t", -1);
            ortho.put(parts[0], Integer.parseInt(parts[1]));
        });
        return new PunktSentenceTokenizer(abbrevs, collocations, starters, ortho);
    }

    private static void readLines(String resource, Consumer<String> sink){
        try(InputStream in = PunktSentenceTokenizer.class.getResourceAsStream(resource)){
            if(in == null){
                throw new IllegalStateException(resource + " is missing from the classpath");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while((line = reader.readLine()) != null){
                sink.accept(line);
            }
        }catch (IOException e){
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------- tokens

    private final class Token {
        final String tok;
        final String type;
        final boolean periodFinal;
        boolean sentbreak;
        boolean abbr;
        boolean ellipsis;

        Token(String tok){
            this.tok = tok;
            this.type = RE_NUMERIC.matcher(PyStrings.lower(tok)).replaceAll("##number##");
            this.periodFinal = tok.endsWith(".");
        }

        String typeNoPeriod(){
            if(PyStrings.len(type) > 1 && type.endsWith(".")){
                return type.substring(0, type.length() - 1);
            }
            return type;
        }

        String typeNoSentperiod(){
            return sentbreak? typeNoPeriod() : type;
        }

        boolean firstUpper(){
            return Character.isUpperCase(tok.codePointAt(0));
        }

        boolean firstLower(){
            return Character.isLowerCase(tok.codePointAt(0));
        }

        boolean isEllipsis(){
            return RE_ELLIPSIS.matcher(tok).lookingAt();
        }

        boolean isInitial(){
            return RE_INITIAL.matcher(tok).lookingAt();
        }
    }

    // ---------------------------------------------------------------------------------------------- tokenizing

    /**
     * NLTK's {@code PunktSentenceTokenizer.tokenize(text)}: the sentences of the text, with boundaries realigned.
     */
    public List<String> tokenize(String text){
        List<String> sentences = new ArrayList<>();
        for(int[] s: realignBoundaries(text, slicesFromText(text))){
            sentences.add(slice(text, s[0], s[1]));
        }
        return sentences;
    }

    /**
     * Python's {@code text[start:stop]}, which is empty rather than an error when start is past stop.
     */
    private static String slice(String text, int start, int stop){
        start = Math.max(0, Math.min(start, text.length()));
        stop = Math.max(0, Math.min(stop, text.length()));
        return start >= stop? "" : text.substring(start, stop);
    }

    private record EndContext(int matchStart, int matchEnd, String nextTok, int nextTokStart, String context){}

    private List<EndContext> matchPotentialEndContexts(String text){
        List<EndContext> out = new ArrayList<>();
        int prevStart = 0;
        int prevStop = 0;
        int[] previous = null; //start, end, nextTokStart (or -1)
        String previousGroup = null;
        String previousAfterTok = null;
        String previousNextTok = null;

        Matcher m = PERIOD_CONTEXT.matcher(text);
        while(m.find()){
            String beforeText = slice(text, prevStop, m.start());
            int indexAfterLastSpace = lastWhitespaceIndex(beforeText);
            if(indexAfterLastSpace != 0){
                indexAfterLastSpace += prevStop + 1;
            }else{
                indexAfterLastSpace = prevStart;
            }
            int wordStart = indexAfterLastSpace;
            int wordStop = m.start();

            if(previous != null && prevStop <= wordStart){
                out.add(new EndContext(previous[0], previous[1], previousNextTok, previous[2],
                        slice(text, prevStart, prevStop) + previousGroup + previousAfterTok));
            }
            previous = new int[]{m.start(), m.end(), m.group("nextTok") == null? -1 : m.start("nextTok")};
            previousGroup = m.group();
            previousAfterTok = m.group("afterTok");
            previousNextTok = m.group("nextTok");
            prevStart = wordStart;
            prevStop = wordStop;
        }
        if(previous != null){
            out.add(new EndContext(previous[0], previous[1], previousNextTok, previous[2],
                    slice(text, prevStart, prevStop) + previousGroup + previousAfterTok));
        }
        return out;
    }

    /**
     * NLTK's {@code _get_last_whitespace_index}: the index of the last character of {@code string.whitespace}, or 0.
     */
    private static int lastWhitespaceIndex(String text){
        for(int i = text.length() - 1; i >= 0; i--){
            if(" \t\n\r\u000b\u000c".indexOf(text.charAt(i)) >= 0){
                return i;
            }
        }
        return 0;
    }

    private List<int[]> slicesFromText(String text){
        List<int[]> slices = new ArrayList<>();
        int lastBreak = 0;
        for(EndContext ctx: matchPotentialEndContexts(text)){
            if(textContainsSentbreak(ctx.context())){
                slices.add(new int[]{lastBreak, ctx.matchEnd()});
                lastBreak = ctx.nextTok() != null && !ctx.nextTok().isEmpty()? ctx.nextTokStart() : ctx.matchEnd();
            }
        }
        slices.add(new int[]{lastBreak, PyStrings.rstrip(text).length()});
        return slices;
    }

    private List<int[]> realignBoundaries(String text, List<int[]> slices){
        List<int[]> out = new ArrayList<>();
        int realign = 0;
        for(int i = 0; i < slices.size(); i++){
            int[] s1 = {slices.get(i)[0] + realign, slices.get(i)[1]};
            int[] s2 = i + 1 < slices.size()? slices.get(i + 1) : null;
            if(s2 == null){
                if(!slice(text, s1[0], s1[1]).isEmpty()){
                    out.add(s1);
                }
                continue;
            }
            Matcher m = BOUNDARY_REALIGNMENT.matcher(slice(text, s2[0], s2[1]));
            if(m.lookingAt()){
                out.add(new int[]{s1[0], s2[0] + PyStrings.rstrip(m.group()).length()});
                realign = m.end();
            }else{
                realign = 0;
                if(!slice(text, s1[0], s1[1]).isEmpty()){
                    out.add(s1);
                }
            }
        }
        return out;
    }

    /**
     * NLTK's {@code text_contains_sentbreak}: true if a token other than the last is a sentence break.
     */
    private boolean textContainsSentbreak(String text){
        List<Token> tokens = tokenizeWords(text);
        for(Token t: tokens){
            firstPassAnnotation(t);
        }
        boolean found = false;
        for(int i = 0; i < tokens.size(); i++){
            //The second pass annotates each token before it is looked at, from the token after it.
            secondPassAnnotation(tokens.get(i), i + 1 < tokens.size()? tokens.get(i + 1) : null);
            if(found){
                return true;
            }
            if(tokens.get(i).sentbreak){
                found = true;
            }
        }
        return false;
    }

    private List<Token> tokenizeWords(String plaintext){
        List<Token> tokens = new ArrayList<>();
        for(String line: plaintext.split("\n", -1)){
            if(PyStrings.strip(line).isEmpty()){
                continue;
            }
            Matcher m = WORD_TOKENIZER.matcher(line);
            while(m.find()){
                tokens.add(new Token(m.group()));
            }
        }
        return tokens;
    }

    private void firstPassAnnotation(Token t){
        String tok = t.tok;
        if(SENT_END_CHARS.contains(tok)){
            t.sentbreak = true;
        }else if(t.isEllipsis()){
            t.ellipsis = true;
        }else if(t.periodFinal && !tok.endsWith("..")){
            String withoutPeriod = PyStrings.lower(tok.substring(0, tok.length() - 1));
            String[] dashParts = withoutPeriod.split("-", -1);
            if(abbrevTypes.contains(withoutPeriod) || abbrevTypes.contains(dashParts[dashParts.length - 1])){
                t.abbr = true;
            }else{
                t.sentbreak = true;
            }
        }
    }

    private enum Ortho {TRUE, FALSE, UNKNOWN}

    private void secondPassAnnotation(Token t1, Token t2){
        if(t2 == null || !t1.periodFinal){
            return;
        }
        String typ = t1.typeNoPeriod();
        String nextTyp = t2.typeNoSentperiod();
        boolean tokIsInitial = t1.isInitial();

        if(collocations.contains(List.of(typ, nextTyp))){
            t1.sentbreak = false;
            t1.abbr = true;
            return;
        }

        if((t1.abbr || t1.ellipsis) && !tokIsInitial){
            Ortho isSentStarter = orthoHeuristic(t2);
            if(isSentStarter == Ortho.TRUE){
                t1.sentbreak = true;
                return;
            }
            if(t2.firstUpper() && sentStarters.contains(nextTyp)){
                t1.sentbreak = true;
                return;
            }
        }

        if(tokIsInitial || typ.equals("##number##")){
            Ortho isSentStarter = orthoHeuristic(t2);
            if(isSentStarter == Ortho.FALSE){
                t1.sentbreak = false;
                t1.abbr = true;
                return;
            }
            if(isSentStarter == Ortho.UNKNOWN && tokIsInitial && t2.firstUpper()
                    && (orthoContext.getOrDefault(nextTyp, 0) & ORTHO_LC) == 0){
                t1.sentbreak = false;
                t1.abbr = true;
            }
        }
    }

    private Ortho orthoHeuristic(Token t){
        if(PUNCTUATION.contains(t.tok)){
            return Ortho.FALSE;
        }
        int context = orthoContext.getOrDefault(t.typeNoSentperiod(), 0);
        if(t.firstUpper() && (context & ORTHO_LC) != 0 && (context & ORTHO_MID_UC) == 0){
            return Ortho.TRUE;
        }
        if(t.firstLower() && ((context & ORTHO_UC) != 0 || (context & ORTHO_BEG_LC) == 0)){
            return Ortho.FALSE;
        }
        return Ortho.UNKNOWN;
    }
}
