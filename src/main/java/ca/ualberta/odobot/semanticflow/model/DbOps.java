package ca.ualberta.odobot.semanticflow.model;

import ca.ualberta.odobot.sqlite.impl.DbLogEntry;
import io.vertx.codegen.doc.Tag;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;


public class DbOps extends ArrayList<DbLogEntry> {
    private static final Logger log = LoggerFactory.getLogger(DbOps.class);

    public enum Verb{
        CREATE(Set.of("new", "create", "add","insert")),
        EDIT(Set.of("edit","modify","update", "change", "save")),
        DELETE(Set.of("delete", "remove", "destroy", "trash"));

        Set<String> associatedTerms;

        Verb(Set<String> associatedTerms){
            this.associatedTerms = associatedTerms;
        }
    }

    public JsonArray subjectSupport = new JsonArray();
    public JsonArray verbSupport = new JsonArray();

    public DbOps(){super();}

    public DbOps(List<DbLogEntry> entries){
        super(entries);
    }

    public Verb computeVerb(Function<String,Double> subjectAuxilliarySupportFunction, Function<String,Double> verbSupportFunction){

        //Handle Create: INSERT type commands signal creates. If the winning subject has any INSERT entries we'll return create.
        String subject = computeSubject(subjectAuxilliarySupportFunction);
        long insertCount = stream().filter(entry -> getTableName(entry).equals(subject) && entry.command().equals("INSERT")).count();

        if(insertCount > 0){
            return Verb.CREATE;
        }else{
        //If no INSERT type commands exist for the winning subject we have to disambiguate between EDIT and DELETE operations.

            double editSupport = 0;
            double deleteSupport = 0;
            Iterator<String> it = Verb.EDIT.associatedTerms.iterator();
            while(it.hasNext()){
                String editTerm = it.next();
                editSupport += verbSupportFunction.apply(editTerm);
            }

            it = Verb.DELETE.associatedTerms.iterator();
            while (it.hasNext()){
                String deleteTerm = it.next();
                deleteSupport += verbSupportFunction.apply(deleteTerm);
            }

            log.info("Edit Support: {}  Delete Support: {}", editSupport, deleteSupport);

            return editSupport > deleteSupport ? Verb.EDIT:Verb.DELETE;

        }
    }

    /**
     * Return the table with most support.
     * @param auxilliarySupportFunction
     * @return
     */
    public String computeSubject(Function<String, Double> auxilliarySupportFunction){

        Optional<Map.Entry<String,Double>> subjectEntry = computeSupport(auxilliarySupportFunction)
                .entrySet().stream()
                .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                .limit(1)
                .findFirst();

        return subjectEntry.isPresent()?subjectEntry.get().getKey():null;

    }



    public LinkedHashMap<String,Double> computeSupport(Function<String, Double> auxilliarySupportFunction){

        LinkedHashMap<String,Integer> databaseSupport = computeTableFrequencies();
        final LinkedHashMap<String, Double> totalSupport = new LinkedHashMap<>();
        databaseSupport.forEach((candidate, frequency)->{
            Double candidateSupport = frequency.doubleValue() * auxilliarySupportFunction.apply(candidate);

            if (candidateSupport != 0){ //Eliminate candidates with 0 support
                totalSupport.put(candidate, candidateSupport);
            }
        });

        LinkedHashMap<String, Double> result = totalSupport.entrySet().stream()
                .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (e1,e2)->e1,LinkedHashMap::new
                ));

        return result;
    }


    public LinkedHashMap<String,Integer> computeTableFrequencies(){
        Map<String, Integer> tableFrequencies = new HashMap<>();

        forEach(entry -> {
            String tableName = getTableName(entry);

            int count = tableFrequencies.getOrDefault(tableName, 0);
            tableFrequencies.put(tableName, ++count);
        });

        LinkedHashMap<String,Integer> sorted = tableFrequencies.entrySet().stream()
                .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (e1, e2)->e1, LinkedHashMap::new));

        return sorted;
    }


    public LinkedHashMap<Verb, List<List<String>>> getVerbGrams(){
        LinkedHashMap result = new LinkedHashMap();
        result.put(Verb.CREATE, Verb.CREATE.associatedTerms.stream().map(s->List.of(s)).collect(Collectors.toList()));
        result.put(Verb.EDIT, Verb.EDIT.associatedTerms.stream().map(s->List.of(s)).collect(Collectors.toList()));
        result.put(Verb.DELETE, Verb.DELETE.associatedTerms.stream().map(s->List.of(s)).collect(Collectors.toList()));

        return result;
    }

    /**
     * Note: this is not an exhaustive list of n-grams
     * TODO: make a more comprehensive set of n-grams
     * @return
     */
    public LinkedHashMap<String, List<List<String>>> getTableNGrams(){
        LinkedHashMap map = new LinkedHashMap();
        forEach(entry -> {
            String tableName = getTableName(entry);

            String [] components = tableName.split("_");

            List<List<String>> result = new ArrayList<>();

            List<List<String>> oneGrams = Arrays.stream(components).map(s->List.of(s)).collect(Collectors.toList());

            result.addAll(oneGrams);

            List<String> fullGram = Arrays.stream(components).collect(Collectors.toList());

            result.add(fullGram);

            map.put(tableName, result);

        });

        return map;
    }

    public boolean hasInsert(String tableName){
        if(tableName == null){
            return false;
        }
        return stream().anyMatch(logEntry-> getTableName(logEntry).equals(tableName) &&  logEntry.command().equals("INSERT"));
    }

    private String getSchemaPrefix(){
        if(size()<=0){
            log.error("Cannot find schema prefix because there are no underlying DbLogEntries");
            throw new RuntimeException("Cannot find schema prefix because there are no underlying DbLogEntries");
        }

        return get(0).objectName().split("\\.")[0];
    }

    private String getTableName(DbLogEntry entry){
        return entry.objectName().split("\\.")[1];
    }

    public String stringRepresentation(){
        return stream().map(entry->entry.command() + " " +  entry.objectName())
                .collect(StringBuilder::new, (sb,s)->sb.append(s + " "), StringBuilder::append)
                .toString().trim();
    }

    public JsonObject toJson(){

        String stringRepresentation = stringRepresentation();

        int hashCode = new HashCodeBuilder(19, 31)
                .append(stringRepresentation)
                .toHashCode();

        JsonArray entries = stream().map(entry->entry.toJson()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);


        return new JsonObject()
                .put("str", stringRepresentation)
                .put("hashCode", hashCode)
                .put("entries", entries);
    }

}
