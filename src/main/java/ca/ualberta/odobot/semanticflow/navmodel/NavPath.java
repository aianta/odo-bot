package ca.ualberta.odobot.semanticflow.navmodel;

import ca.ualberta.odobot.guidance.execution.ExecutionRequest;
import ca.ualberta.odobot.guidance.execution.InputParameter;
import ca.ualberta.odobot.guidance.instructions.*;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.neo4j.graphdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

public class NavPath {

    private static final Logger log = LoggerFactory.getLogger(NavPath.class);

    public  static  Pattern pattern = Pattern.compile("[a-zA-Z]+");

    private static Map<String,String> globalParameterMap;

    private Path path = null;

    private UUID id = UUID.randomUUID();

    private Iterator<Node> iterator = null;

    //These are the nodes that produce instructions.
    private Predicate<Node> instructionNodePredicate = (node)->
            node.hasLabel(Label.label("ClickNode")) ||
            node.hasLabel(Label.label("DataEntryNode")) ||
            node.hasLabel(Label.label("CheckboxNode")) ||
            node.hasLabel(Label.label("APINode")) ||
            node.hasLabel(Label.label("LocationNode"));

    private Instruction lastInstruction;

    private Set<String> parameters;

    public NavPath(){
    }

    public Instruction lastInstruction(){
        return lastInstruction;
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

    /**
     * Compute which parameters appear in this nav path
     * @param parameterMap
     */
    public Set<String> computeParameters(Map<String,String> parameterMap){
        globalParameterMap = parameterMap; //TODO - should probably refactor this...
        this.parameters = new HashSet<>();

        //Go through all the nodes in this path
        path.nodes().forEach(node->{
            //If the node has an entry in the parameter map, add the id of the parameter node to this nav path's list of parameters.
            String parameterId = parameterMap.get(node.getProperty("id"));
            if(parameterId != null){
                parameters.add(parameterId);
            }
        });

        return this.parameters;
    }

    public Set<String> getParameters(){
        return parameters;
    }


    public void resetPath(){
        iterator = path.nodes().iterator();
    }


    /**
     * For Synapse mind2web guidance. Returns mind2web action ids along this nav path.
     * @param validActionIds
     * @return
     */
    public List<String> getActionIds(Collection<String> validActionIds){
        while (iterator.hasNext()){
            Node node = iterator.next();

            //Instances associated with start and end nodes are annotation ids not action ids
            if(node.hasLabel(Label.label("StartNode")) || node.hasLabel(Label.label("EndNode"))){
                continue;
            }

            return Arrays.stream(((String[])node.getProperty("instances"))) //Get the node's instances, which for are action ids if the node isn't a start or end node.
                    .filter(actionId->validActionIds.contains(actionId)) //Filter out actionIds that do not appear in the valid actions list
                    .toList();
        }

        log.warn("No actionIds left for this path.");
        return List.of();
    }

    public String getXPath(){
        while (iterator.hasNext()){
            Node node = iterator.next();

            //Instances associated with start and end nodes are annotation ids not action ids, and also don't have xpaths
            if(node.hasLabel(Label.label("StartNode")) || node.hasLabel(Label.label("EndNode"))){
                continue;
            }

            return (String)node.getProperty("xpath");
        }

        log.warn("No xpaths left for this path");
        return null;
    }

    public Instruction getExecutionInstruction(ExecutionRequest request){
        while (iterator.hasNext()){
            Node node = iterator.next();

            if(instructionNodePredicate.test(node)){
                Instruction instruction = null;

                //Handle collapsed nodes
                if(node.hasLabel(Label.label("CollapsedClickNode")) ||
                        node.hasLabel(Label.label("CollapsedDataEntryNode")) ||
                        node.hasLabel(Label.label("CollapsedCheckboxNode"))
                ){
                    QueryDom _instruction = new QueryDom();
                    _instruction.dynamicXPath = nodeToDynamicXPath(node);
                    _instruction.parameterId = LogPreprocessor.neo4j.getAssociatedParameterId((String)node.getProperty("id")); //This is going to cause problems for any collapsed click node or data entry node that doesn't have a schema parameter...
                    instruction = _instruction;
                }else{

                    if(node.hasLabel(Label.label("APINode"))){
                        WaitForNetworkEvent _instruction = new WaitForNetworkEvent();
                        _instruction.method = (String) node.getProperty("method");
                        _instruction.path = (String) node.getProperty("path");
                        instruction = _instruction;
                    }

                    if(node.hasLabel(Label.label("LocationNode"))){
                        WaitForLocationChange _instruction = new WaitForLocationChange();
                        _instruction.path = (String)node.getProperty("path");
                        instruction = _instruction;
                    }

                    if(node.hasLabel(Label.label("CheckboxNode"))){
                        log.info("Instruction is a checkbox node!");
                        /**
                         * TODO: proper checkbox support should probably be explicit on whether the checkbox in question should be
                         * checked or not.
                         */
                        DoClick _instruction = new DoClick();
                        _instruction.xpath = nodeToXPath(node);
                        instruction = _instruction;
                    }

                    if(node.hasLabel(Label.label("DataEntryNode"))){

                        EnterData _instruction = new EnterData();
                        _instruction.xpath = nodeToXPath(node);
                        _instruction.parameterId = LogPreprocessor.neo4j.getAssociatedParameterId((String)node.getProperty("id")); //This is going to cause problems for any data entry node that doesn't have an input parameter...
                        _instruction.data = ((InputParameter)request.getParameter(_instruction.parameterId)).getValue();
                        instruction = _instruction;
                    }

                    if(node.hasLabel(Label.label("ClickNode"))){
                        DoClick _instruction = new DoClick();
                        _instruction.xpath = nodeToXPath(node);
                        instruction = _instruction;
                    }

                }

                lastInstruction = instruction;
                return instruction;

            }


        }

        log.warn("No valid instruction nodes left in path {}!", id.toString());
        lastInstruction = null;
        return null;
    }

    public Instruction getInstruction(){


        while (iterator.hasNext()){
            Node node = iterator.next();

            if(instructionNodePredicate.test(node)){
                Instruction instruction = null;


                if(node.hasLabel(Label.label("CollapsedClickNode")) ||
                        node.hasLabel(Label.label("CollapsedDataEntryNode"))
                ){
                    DynamicXPathInstruction dynamicXPathInstruction = new DynamicXPathInstruction();
                    dynamicXPathInstruction.dynamicXPath = nodeToDynamicXPath(node);
                    instruction = dynamicXPathInstruction;
                }else{
                    XPathInstruction xPathInstruction = new XPathInstruction();
                    xPathInstruction.xpath = nodeToXPath(node);
                    instruction = xPathInstruction;
                }

                lastInstruction = instruction;
                return instruction;
            }
        }

        log.warn("No valid instruction nodes left in path {}!", id.toString());
        lastInstruction = null;
        return null;
    }


//    public JsonObject getInstruction(){
//        JsonObject result = new JsonObject()
//                .put("id", id.toString());
//
//        while (iterator.hasNext()){
//            Node node = iterator.next();
//
//            if(instructionNodePredicate.test(node)){
//                JsonObject instruction = null;
//
//                if(node.hasLabel(Label.label("CollapsedClickNode")) ||
//                        node.hasLabel(Label.label("CollapsedDataEntryNode"))
//                ){
//                    instruction = makeInstructionFromCollapsedNode(node);
//                }else{
//                    instruction = makeInstructionFromNode(node);
//                }
//
//                result.mergeIn(makeInstructionFromNode(node));
//                return result;
//            }
//        }
//
//        log.warn("No valid instruction nodes left in path {}!", id.toString());
//        return null;
//
//    }

    private JsonObject makeInstructionFromNode(Node n){
        JsonObject result = new JsonObject()
                .put("xpath", (String)n.getProperty("xpath"));

        return result;
    }

    private String nodeToXPath(Node n){
        if(!n.hasProperty("xpath")){
            log.error("Node does not have xpath property!");
            throw new RuntimeException("Node does not have xpaths property!");
        }

        return (String)n.getProperty("xpath");
    }

    //TODO: This method is also used by the snippet extraction logic, should probably move this to a common/util or refactor in some other way.
    public static DynamicXPath nodeToDynamicXPath(Node n){
        if(!n.hasProperty("xpaths")){
            log.error("Node does not have xpaths property!");
            throw new RuntimeException("Node does not have xpaths property!");
        }

        String [] xpaths = (String[]) n.getProperty("xpaths");
        return findDynamicXPath(xpaths);
    }

    private JsonObject makeInstructionFromCollapsedNode(Node n){
        JsonObject result = new JsonObject();

        if(!n.hasProperty("xpaths")){
            log.error("Node does not have xpaths property!");
            throw new RuntimeException("Node does not have xpaths property!");
        }

        String [] xpaths = (String[]) n.getProperty("xpaths");
        result.put("xpath", findDynamicXPath(xpaths).toJson());

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

        //Determine how long of a common sequence there is between xpaths starting from the first character.
        while (length < first.length()){
            final int _length = length;
            if(Arrays.stream(xpaths).allMatch(example->example.regionMatches(true, 0, first, 0, _length))){
                length++;
            }else{
                break;
            }
        }

        final int MATCHING_LENGTH = length; //Matching length is the number of characters from the start of the xpaths that are identical.

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

        String tag = extractTag(tagString);
        log.info("tag: {}", tag);

        DynamicXPath dXpath = new DynamicXPath();
        dXpath.setPrefix(prefix);
        dXpath.setSuffix(suffix);
        dXpath.setDynamicTag(tag);

        return dXpath;
    }

    public static String extractTag(String input){
        Matcher matcher = pattern.matcher(input);
        matcher.find();
        return matcher.group();
    }

    public static void saveNavPath(String filename, NavPath path){
        File fout = new File(filename);
        try(FileWriter fw = new FileWriter(fout);
            BufferedWriter bw = new BufferedWriter(fw);
        ){

            StringBuilder sb = new StringBuilder();
            path.getPath().nodes().forEach(n->{
                StringBuilder nsb = new StringBuilder();
                nsb.append("(");
                n.getLabels().forEach(label->nsb.append(":" + label.name()));
                nsb.append("| id:%s)".formatted((String)n.getProperty("id")));
                nsb.append("-->");
                sb.append(nsb.toString());
            });

            bw.write(sb.toString());
            bw.flush();

        } catch (IOException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

    }

    public static void printNavPaths(List<NavPath> paths, int limit){
        IntStream.range(0, paths.size())
                .limit(limit)
                .forEach(i->{


                    StringBuilder sb = new StringBuilder();
                    paths.get(i).getPath().nodes().forEach(n->{
                        StringBuilder nsb = new StringBuilder();
                        nsb.append("(");
                        n.getLabels().forEach(label->nsb.append(":" + label.name()));
                        nsb.append("| id:%s)".formatted((String)n.getProperty("id")));
                        nsb.append("-->");
                        sb.append(nsb.toString());
                    });

                    log.info("Path[{}] length: {}: {}", i, paths.get(i).getPath().length(), sb.toString());

                });
    }

    public List<String> toNaturalLanguage(JsonArray parameters){

        printNavPaths(List.of(this), 10);

        List<String> result = new ArrayList<>();

        Iterator<Node> it = path.nodes().iterator();
        while (it.hasNext()){
            Node curr = it.next();
            String currNodeId = (String)curr.getProperty("id");

            //Need to handle collapsed click nodes first.
            if(curr.hasLabel(Label.label("CollapsedClickNode")) && curr.hasRelationship(Direction.OUTGOING, RelationshipType.withName("PARAM"))){
                JsonObject parameterMapping = getParameterById(getAssociatedSchemaParameterId(curr), parameters);
                String schemaName = getAssociatedSchemaName(curr);
                result.add("Select the %s '%s'.".formatted(schemaName, parameterMapping.getString("query")));
                continue;
            }

            if(curr.hasLabel(Label.label("ClickNode"))){

                String btnText = "";
                if(curr.hasProperty("text")){
                    btnText = (String) curr.getProperty("text");
                }

                //If we're dealing with collapsed click
//                if(curr.hasProperty("texts") && ((List<String>)curr.getProperty("texts")).size() == 1){
//                    btnText = ((List<String>)curr.getProperty("texts")).get(0);
//                }


                //If the text property of the button isn't blank.
                if(!btnText.isBlank() && !btnText.isEmpty() && btnText != null){
                    result.add("Click on the '%s' button.".formatted((String)curr.getProperty("text")));
                }else{
                    result.add("Click on a button.");
                }
                continue;
            }

            if(curr.hasLabel(Label.label("DataEntryNode"))){
                JsonObject parameterMapping = getParameterById(getAssociatedInputParameterId(curr), parameters);
                if(parameterMapping == null){
                    continue;
                }
                String parameterLabel = getAssociatedInputParameterLabel(curr);
                result.add("Enter '%s' as the %s value.".formatted(parameterMapping.getString("value"), parameterLabel));
                continue;
            }

            if(curr.hasLabel(Label.label("CheckboxNode"))){
                String parameterLabel = getAssociatedInputParameterLabel(curr);
                result.add("Click the '%s' checkbox.".formatted(parameterLabel));
                continue;
            }

        }

        return result;
    }

    /**
     * For use in {@link #toNaturalLanguage(JsonArray)}
     * @param id
     * @param parameters
     * @return
     */
    private JsonObject getParameterById(String id, JsonArray parameters){
        log.info("Looking for id: {}", id);
        return parameters.stream().map(o->(JsonObject)o).filter(param->param.getString("id").equals(id)).findFirst().orElseGet(()->null);
    }

    /**
     * For use in {@link #toNaturalLanguage(JsonArray)}
     * @param node
     * @return
     */
    private String getAssociatedSchemaName(Node node){
        Relationship r = node.getRelationships(Direction.OUTGOING, RelationshipType.withName("PARAM")).iterator().next();
        return (String)r.getEndNode().getProperty("name");
    }

    private String getAssociatedSchemaParameterId(Node node){
        Relationship r = node.getRelationships(Direction.OUTGOING, RelationshipType.withName("PARAM")).iterator().next();
        return (String)r.getEndNode().getProperty("id");
    }

    private String getAssociatedInputParameterId(Node node){
        Relationship r = node.getRelationships(Direction.OUTGOING, RelationshipType.withName("PARAM")).iterator().next();
        return (String)r.getEndNode().getProperty("id");
    }

    /**
     * For use in {@link #toNaturalLanguage(JsonArray)}
     * @param node
     * @return
     */
    private String getAssociatedInputParameterLabel(Node node){
        Relationship r = node.getRelationships(Direction.OUTGOING, RelationshipType.withName("PARAM")).iterator().next();
        return (String)r.getEndNode().getProperty("label");
    }


}
