package ca.ualberta.odobot.semanticflow.navmodel;

import ca.ualberta.odobot.common.BasePathAndXpath;
import ca.ualberta.odobot.common.Xpath;
import ca.ualberta.odobot.guidance.execution.ExecutionRequest;
import ca.ualberta.odobot.guidance.execution.InputParameter;
import ca.ualberta.odobot.guidance.instructions.*;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.model.RadioButtonEvent;
import ca.ualberta.odobot.semanticflow.model.SelectEvent;
import ca.ualberta.odobot.semanticflow.navmodel.nodes.RadioButtonNode;
import ca.ualberta.odobot.semanticflow.navmodel.nodes.SelectOptionNode;
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
import java.util.stream.Collectors;
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
            node.hasLabel(Label.label("RadioButtonNode")) ||
            node.hasLabel(Label.label("SelectOptionNode")) ||
            node.hasLabel(Label.label("APINode")) ||
            node.hasLabel(Label.label("GraphQLNode")) ||
            node.hasLabel(Label.label("LocationNode"));

    private Instruction lastInstruction;

    private String lastInstructionNodeId; //The id of the node corresponding with the last instruction.

    private Set<String> parameters;

    public NavPath(){
    }

    public NavPath updateLastInstruction(Instruction instruction){
        this.lastInstruction = instruction;
        return this;
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

    /**
     * Returns the id of the last node in the path from which one can attempt to compute a new execution path.
     *
     * There is no guarantee that such a node exists. For example, if the nodes before the failure point change the state of the application.
     *
     * @param failedNodeId the id of the node at which execution failed.
     * @return the id of the node that can act as a starting node for a new path.
     */
    public Optional<String> getRecoverableStartingNodeId(String failedNodeId){

        /**
         * Compile a list of json objects summarizing: node Id, the numeber of out going edges of type NEXT, and the labels associated with the node.
         */
        List<JsonObject> pathInfo = new ArrayList<>();

        path.nodes().forEach(node->{
            JsonObject nodeInfo = new JsonObject()
                    .put("id",node.getProperty("id"))
                    .put("outDegreesOnNext", node.getDegree(RelationshipType.withName("NEXT"), Direction.OUTGOING));

            JsonArray labels = new JsonArray();
            node.getLabels().forEach(label -> labels.add(label.name()));
            nodeInfo.put("labels",labels);

            pathInfo.add(nodeInfo);
        });

        /**
         * Find the node at which the failure occurred
         */
        ListIterator<JsonObject> cursor = pathInfo.listIterator();
        while (cursor.hasNext()){
            JsonObject curr = cursor.next();
            if(curr.getString("id").equals(failedNodeId)){
                break;
            }
            if(!cursor.hasNext() && !curr.getString("id").equals(failedNodeId)){
                log.info("Could not find failed node id in path!");
                return Optional.empty();
            }
        }

        //Cursor should now be pointing to the node at which execution failed.
        while (cursor.hasPrevious()){
            JsonObject candidate = cursor.previous();

            //Don't start from the failed node.
            if(candidate.getString("id").equals(failedNodeId)){
                continue;
            }

            JsonArray candidateLabels = candidate.getJsonArray("labels");

            if((candidateLabels.contains("EffectNode") ||
                candidateLabels.contains("ClickNode") ||
                candidateLabels.contains("DataEntryNode") ||
                candidateLabels.contains("CheckboxNode") ||
                candidateLabels.contains("RadioButtonNode") ||
                candidateLabels.contains("SelectOptionNode")
            ) && (candidate.getInteger("outDegreesOnNext") > 1)){

                log.info("Recovery Starting Node: {}", candidate.encodePrettily());

                return Optional.of(candidate.getString("id"));
            }

            //If we come across one of these nodes we can't go back further because state has very likely changed.
            if(candidateLabels.contains("LocationNode") || candidateLabels.contains("APINode")){
                return Optional.empty();
            }
        }

        return Optional.empty();

    }

    public Instruction getExecutionInstruction(ExecutionRequest request){
        while (iterator.hasNext()){
            Node node = iterator.next();

            if(instructionNodePredicate.test(node)){
                Instruction instruction = null;

                    if(node.hasLabel(Label.label("APINode")) || node.hasLabel(Label.label("GraphQLNode"))){

                        //Do not wait for API or GraphQL operations that seem to have been triggered by nothing.
                        //We say that an API call or GraphQL operation was triggered by nothing if we have observed a tight loop (API/GQL Node A)->(effect)->(API/GQL Node A)
                        //or a self loop (API/GQL Node A)->(API/GQL Node A) in the model.
                        // In these cases, we assume that the API/GQL call in question is being triggered by some automated mechanism in the application, and that waiting for it to occur is not necessary for successful execution.
                        ResourceIterable<Relationship> outgoingEdges = node.getRelationships(Direction.OUTGOING, RelationshipType.withName("NEXT"));
                        ResourceIterable<Relationship> incomingEdges = node.getRelationships(Direction.INCOMING, RelationshipType.withName("NEXT"));

                        //Look for the same effect node appearing both before and after the API/GQL node in question, which would indicate a tight loop.
                        Set<String> nextNodeIds = outgoingEdges.stream()
                                //Only interested in edges leading to effect nodes or API/GQL nodes
                                .filter(r->
                                        (r.getEndNode().hasLabel(Label.label("EffectNode")) || r.getEndNode().hasLabel(Label.label("APINode")) || r.getEndNode().hasLabel(Label.label("GraphQLNode"))))
                                .map(r->(String)r.getEndNode().getProperty("id")).collect(Collectors.toSet());
                        Set<String> previousNodeIds = incomingEdges.stream()
                                .filter(r->
                                        (r.getStartNode().hasLabel(Label.label("EffectNode")) || r.getEndNode().hasLabel(Label.label("APINode")) || r.getEndNode().hasLabel(Label.label("GraphQLNode"))))
                                .map(r->(String)r.getStartNode().getProperty("id")).collect(Collectors.toSet());

                        //Set intersection, if the same node id appears in both set, one of those tight loops exists.
                        nextNodeIds.retainAll(previousNodeIds);

                        if(!nextNodeIds.isEmpty()){
                            continue;
                        }



                        WaitForNetworkEvent _instruction = new WaitForNetworkEvent();
                        _instruction.method = (String) node.getProperty("method");
                        _instruction.path = (String) node.getProperty("path");
                        if(node.hasProperty("operationName")){
                            _instruction.operationName = (String) node.getProperty("operationName");
                        }
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

                    if(node.hasLabel(Label.label("SelectOptionNode"))){
                        log.info("Instruction is a select option node!");
                        GetUIControlState _instruction = new GetUIControlState();
                        _instruction.xpath = nodeToXPath(node);
                        _instruction.type = GetUIControlState.Type.SELECT;
                        _instruction.parameterId = LogPreprocessor.neo4j.getAssociatedParameterId((String)node.getProperty("id")); //This is going to cause problems for any select option node that doesn't have an input parameter...

                        instruction = _instruction;
                    }

                    if(node.hasLabel(Label.label("RadioButtonNode"))){
                        log.info("Instruction is a radio button node!");
                        GetUIControlState _instruction = new GetUIControlState();
                        _instruction.xpath = nodeToXPath(node);
                        _instruction.type = GetUIControlState.Type.RADIO_BUTTON;
                        _instruction.parameterId = LogPreprocessor.neo4j.getAssociatedParameterId((String)node.getProperty("id")); //This is going to cause problems for any select option node that doesn't have an input parameter...


                        instruction = _instruction;
                    }

                    if(node.hasLabel(Label.label("DataEntryNode"))){
                        GetUIControlState _instruction = new GetUIControlState();
                        _instruction.parameterId = LogPreprocessor.neo4j.getAssociatedParameterId((String)node.getProperty("id")); //This is going to cause problems for any data entry node that doesn't have an input parameter...
                        _instruction.xpath = nodeToXPath(node);

                        if (node.hasProperty("editorId")){
                            _instruction.editorId = (String) node.getProperty("editorId");
                            _instruction.type = GetUIControlState.Type.TINY_MCE_EDITOR;
                        }else{
                            _instruction.type = GetUIControlState.Type.TEXT;
                        }
                        instruction = _instruction;


                    }

                    if(node.hasLabel(Label.label("ClickNode"))){

                        if(node.hasProperty("dynamicXpaths")){
                            String [] _dxpaths = (String[])node.getProperty("dynamicXpaths");
                            Set<DynamicXPath> dxpaths = Arrays.stream(_dxpaths)
                                    .map(JsonObject::new)
                                    .map(DynamicXPath::fromJson)
                                    .collect(Collectors.toSet());
                            QueryDom _instruction = new QueryDom();
                            _instruction.dynamicXPaths = dxpaths;
                            instruction = _instruction;

                        } else if(node.hasRelationship(Direction.OUTGOING, RelationshipType.withName("PARAM"))){
                            Relationship r = node.getSingleRelationship(RelationshipType.withName("PARAM"), Direction.OUTGOING);
                            Node parameterNode = r.getEndNode();

                            GetDOMSnapshot _instruction = new GetDOMSnapshot();
                            _instruction.parameterName = parameterNode.getProperty("name").toString();
                            _instruction.parameterId = parameterNode.getProperty("id").toString();
                            instruction = _instruction;

                        } else if (node.hasProperty("dynamicXpath")){
                            DynamicXPath dxpath = DynamicXPath.fromJson(new JsonObject((String) node.getProperty("dynamicXpath")));
                            QueryDom _instruction = new QueryDom();
                            _instruction.dynamicXPath = dxpath;
                            instruction = _instruction;
                        }else {
                            DoClick _instruction = new DoClick();
                            _instruction.xpath = nodeToXPath(node);
                            instruction = _instruction;
                        };


                    }


                instruction.setSourceNodeId((String)node.getProperty("id"));



                /**
                 * In the trajectories/traces there sometimes are data entries during which dom effects occurred. This results in graph structures in the model where DE_1 -> E -> DE_1.
                 * DE_1 is the same data entry, the model simply describes that some dom effects may take place while you type/enter data.
                 *
                 * During execution, we play back this path, but doing so exactly would result in sending DE_1 to OdoX twice. Therefore to prevent that, we check to see if the instruction we're returning
                 * is identical to our last instruction, and if so, we compute the next one.
                 */

                if(instruction.equals(lastInstruction)){
                    return getExecutionInstruction(request);
                }

                lastInstruction = instruction;
                setLastInstructionNodeId((String)node.getProperty("id")); //Set this so we can keep track of which nodes have already been visited.

                return instruction;

            }


        }

        log.warn("No valid instruction nodes left in path {}!", id.toString());
        lastInstruction = null;
        return null;
    }


    private String nodeToXPath(Node n){
        if(!n.hasProperty("xpath")){

            if(!n.hasProperty("xpaths")){
                log.error("Node does not have xpath property!");
                throw new RuntimeException("Node does not have xpaths property!");
            }else{
                String[] xpaths = (String[]) n.getProperty("xpaths");
                if(xpaths.length > 0){
                    /**
                     * Xpaths values are stored in the following format:
                     * ["<baseURI>,<xpath>", ...]
                     */

                    return xpaths[0].split(",")[1];
                }else{
                    log.error("Node's xpaths property contains no xpaths!");
                }
            }


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
        String nodeId = (String)n.getProperty("id");
        log.info("NodeId: {}", nodeId);
        Set<Xpath> uniqueXpaths = Arrays.stream(xpaths).map(BasePathAndXpath::fromString).map(BasePathAndXpath::getXpath).collect(Collectors.toSet());
        if(uniqueXpaths.size() > 1){
            return findDynamicXPath(uniqueXpaths.stream().map(Xpath::toString).collect(Collectors.toSet()).toArray(new String[0]));
        }else{
            log.info("Node {} does not contain a dynamic xpath.", (String)n.getProperty("id"));
            return null;
        }


    }




    public String getLastInstructionNodeId() {
        return lastInstructionNodeId;
    }

    public NavPath setLastInstructionNodeId(String lastInstructionNodeId) {
        this.lastInstructionNodeId = lastInstructionNodeId;
        return this;
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

        log.info("Computing dynamic xpath from xpaths:");
        Arrays.stream(xpaths).forEach(s->log.info("{}", s));

        //Prune any xpaths that go beyond svgs. For example .../svg/g/path becomes .../svg
        //TODO: these should probably be pruned way earlier...but whatever, we can do that later.
        for(int i=0;i<xpaths.length;i++){
            String xpath = xpaths[i];
            if (xpath.lastIndexOf("svg") != -1){
                xpaths[i] = xpath.substring(0, xpath.lastIndexOf("svg") + 3);
            }
        }

        //Ensure longest entry first
        Arrays.sort(xpaths, Comparator.comparing(s->((String)s).length()).reversed());



        //For debugging
        for(String xpath: xpaths){
            log.info("{}", xpath);
        }

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

        log.info("Working on suffix");
        List<String> suffixes = Arrays.stream(xpaths).map(s->{
            try{
                var temp = s.substring(MATCHING_LENGTH);
                return temp.substring(temp.indexOf("/")+1);
            }catch (StringIndexOutOfBoundsException e){
                return null; //If there is a sample where there is no suffix, discard it.
            }


        }).filter(Objects::nonNull).
                toList();
        Pattern suffixPattern = DynamicXPath.toSuffixPattern(suffixes);




        log.info("Prefix: {} SuffixPattern: {}", prefix, suffixPattern.pattern());
        String tagString = first.substring(prefix.length());

        var matcher = suffixPattern.matcher(first);
        if(matcher.find()){
            var suffix = matcher.group(0);
            tagString = tagString.substring(0, tagString.length()-suffix.length());
        }

        log.info("tagString: {}", tagString);

        String tag = extractTag(tagString);
        if(tag == null){
            return null;
        }
        log.info("tag: {}", tag);

        DynamicXPath dXpath = new DynamicXPath();
        dXpath.setPrefix(prefix);
        //dXpath.setSuffix(suffix);
        dXpath.setSuffixPattern(suffixPattern);
        dXpath.setKnownSuffixes(suffixes);
        dXpath.setDynamicTag(tag);

        return dXpath;
    }

    public static String extractTag(String input){
        log.info("extracting tag from: {}", input);
        try{
            Matcher matcher = pattern.matcher(input);
            matcher.find();
            return matcher.group();
        }catch (IllegalStateException e){
            log.warn(e.getMessage(), e);
            return null;
        }

    }

    public String makeCypherQueryForNodes(){

        StringBuilder sbIds =  new StringBuilder();
        sbIds.append("[");
        Iterator<Node> it = getPath().nodes().iterator();
        while (it.hasNext()){
            Node node = it.next();
            sbIds.append("\"%s\"".formatted(node.getProperty("id")));
            if(it.hasNext()){
                sbIds.append(",");
            }
        }
        sbIds.append("]");


        return "MATCH (n)-[r]-(m) WHERE n.id IN %s AND m.id IN %s RETURN n,m,r;".formatted(sbIds.toString(), sbIds.toString());
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

            sb.append("\n\n");
            sb.append(path.makeCypherQueryForNodes());

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

                //Handle unmapped resource parameter nodes. Something we allow as of March 27, 2026 in cases where these nodes are executable by their dynamicXpaths property
                if(parameterMapping == null){
                    result.add("Select the task appropriate item.");
                    continue;
                }

                String schemaName = getAssociatedSchemaName(curr);
                result.add("Select the %s '%s'.".formatted(schemaName, parameterMapping.getString("query")));
                continue;
            }

            if(curr.hasLabel(Label.label("ClickNode"))){

                String btnText = "";
                if(curr.hasProperty("text")){
                    btnText = (String) curr.getProperty("text");
                }



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

            if (curr.hasLabel(Label.label("SelectOptionNode"))){
                //TODO: this will break if we ever have a collapsed select option node, but we can deal with that later.
                List<SelectEvent.Option> options = SelectOptionNode.optionsFromStrings((String[])curr.getProperty("options"));
                String parameterLabel = getAssociatedInputParameterLabel(curr);
                StringBuilder sb = new StringBuilder();
                Iterator<SelectEvent.Option> optionIterator = options.iterator();
                while (optionIterator.hasNext()){
                    SelectEvent.Option option = optionIterator.next();
                    sb.append("'%s'".formatted(option.label()));
                    if(optionIterator.hasNext()){
                        sb.append(", ");
                    }
                }

                result.add("Select the appropriate option from the '%s' dropdown. The available options are: [%s]".formatted(parameterLabel, sb.toString()));
                continue;
            }

            if(curr.hasLabel(Label.label("RadioButtonNode"))){
                String radioGroup = (String)curr.getProperty("radioGroup");
                List<RadioButtonEvent.RadioButton> options = RadioButtonNode.getRadioButtonsFromStrings(Arrays.stream(((String [])curr.getProperty("relatedElements"))).toList());
                StringBuilder optionsStringBuilder = new StringBuilder();
                Iterator<RadioButtonEvent.RadioButton> buttonIterator = options.iterator();
                while (buttonIterator.hasNext()){
                    RadioButtonEvent.RadioButton button = buttonIterator.next();
                    optionsStringBuilder.append("'%s'".formatted(button.getValue()));
                    if(buttonIterator.hasNext()){
                        optionsStringBuilder.append(", ");
                    }
                }

                result.add("Select the appropriate option for the '%s' radio button group. The available options are: [%s]".formatted(radioGroup, optionsStringBuilder.toString()));
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
