package ca.ualberta.odobot.semanticflow.navmodel;

import io.vertx.core.json.JsonObject;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NavPath {

    private static final Logger log = LoggerFactory.getLogger(NavPath.class);

    private Path path = null;

    private UUID id = UUID.randomUUID();

    private Iterator<Node> iterator = null;

    private Predicate<Node> instructionNodePredicate = (node)->node.hasLabel(Label.label("ClickNode")) || node.hasLabel(Label.label("DataEntryNode"));

    public NavPath(){
    }

    public UUID getId() {
        return id;
    }

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
        iterator = path.nodes().iterator();
    }

    public void resetPath(){
        iterator = path.nodes().iterator();
    }

    public JsonObject getInstruction(){
        JsonObject result = new JsonObject()
                .put("id", id.toString());

        while (iterator.hasNext()){
            Node node = iterator.next();

            if(instructionNodePredicate.test(node)){
                JsonObject instruction = null;

                if(node.hasLabel(Label.label("CollapsedClickNode")) ||
                        node.hasLabel(Label.label("CollapsedDataEntryNode"))
                ){
                    instruction = makeInstructionFromCollapsedNode(node);
                }else{
                    instruction = makeInstructionFromNode(node);
                }

                result.mergeIn(makeInstructionFromNode(node));
                return result;
            }
        }

        log.warn("No valid instruction nodes left in path {}!", id.toString());
        return null;

    }

    private JsonObject makeInstructionFromNode(Node n){
        JsonObject result = new JsonObject()
                .put("xpath", (String)n.getProperty("xpath"));

        return result;
    }

    private JsonObject makeInstructionFromCollapsedNode(Node n){
        JsonObject result = new JsonObject();

        if(!n.hasProperty("xpaths")){
            log.error("Node does not have xpaths property!");
            throw new RuntimeException("Node does not have xpaths property!");
        }

        String [] xpaths = (String[]) n.getProperty("xpaths");
        result.put("xpath", findCommonXpath(xpaths));

        return result;
    }






    public static String findCommonXpath(String [] xpaths){

        if(xpaths.length == 0){
            return null;
        }

        String first = xpaths[0];
        int length = 1;

        while (length < first.length()){
            final int _length = length;
            if(Arrays.stream(xpaths).allMatch(example->example.regionMatches(true, 0, first, 0, _length))){
                length++;
            }else{
                break;
            }
        }


        final int MATCHING_LENGTH = length;
        log.info("Matching length was: {}", MATCHING_LENGTH);
        return Arrays.stream(xpaths).map(s->{
                   String slice = s.substring(0, MATCHING_LENGTH);
                   slice = slice.substring(0,slice.lastIndexOf("/"));
                   return slice;
        })
                .peek(s->System.out.println(s))
                .findFirst()
                .get();

    }

    public static DynamicXPath findDynamicXPath(String [] xpaths){

        if(xpaths.length == 0){
            return null;
        }

        //Ensure longest entry first
        Arrays.sort(xpaths, Comparator.comparing(s->((String)s).length()).reversed());

        String first = xpaths[0];
        int length = 1;

        while (length < first.length()){
            final int _length = length;
            if(Arrays.stream(xpaths).allMatch(example->example.regionMatches(true, 0, first, 0, _length))){
                length++;
            }else{
                break;
            }
        }


        final int MATCHING_LENGTH = length;
        log.info("Matching length was: {}", MATCHING_LENGTH);
        String prefix = Arrays.stream(xpaths).map(s->{
                    String slice = s.substring(0, MATCHING_LENGTH);
                    slice = slice.substring(0,slice.lastIndexOf("/"));
                    return slice;
                })
                .peek(s->System.out.println(s))
                .findFirst()
                .get();

        length = 2;

        log.info("Working on suffix");
        while (length < first.length()){

            final int _length = length;

            if(Arrays.stream(xpaths).allMatch(example->example.substring(example.length()-_length).equals(first.substring(first.length()-_length)))){
                length++;
            }else {
                break;
            }
        }

        final int MATCHING_SUFFIX_LENGTH = length;
        log.info("Matching suffix length was: {}", MATCHING_SUFFIX_LENGTH);
        String suffix = Arrays.stream(xpaths).map(s->{
            String slice = s.substring(s.length()-MATCHING_SUFFIX_LENGTH);
            if(!slice.contains("/")){
                throw new RuntimeException("Suffix doesn't contain any forward slashes, how'd you manage that? Suffix: " + slice);
            }
            slice = slice.substring(slice.indexOf("/"));
            return slice;
        }).peek(s->System.out.println(s))
                .findFirst()
                .get();

        log.info("Prefix: {} Suffix: {}", prefix, suffix);

        String tagString = first.substring(prefix.length());
        tagString = tagString.substring(0, tagString.length()-suffix.length());
        log.info("tagString: {}", tagString);

        Pattern pattern = Pattern.compile("[a-zA-Z]+");
        Matcher matcher = pattern.matcher(tagString);
        matcher.find();

        String tag = matcher.group();
        log.info("tag: {}", tag);

        DynamicXPath dXpath = new DynamicXPath();
        dXpath.setPrefix(prefix);
        dXpath.setSuffix(suffix);
        dXpath.setDynamicTag(tag);

        return dXpath;
    }



}
