package ca.ualberta.odobot.explorer.model;


import io.vertx.core.json.JsonArray;

import java.util.ArrayList;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * @Author
 *
 * A class for managing an ordered list of operations
 */
public class ToDo extends ArrayList<Operation> {
    private static final Random random = new Random();

    @Override
    public boolean add(Operation operation) {
        //Don't add duplicate operations (with the same ids).
        if(operationIds().contains(operation.getId())){
            return false;
        }else{
            return super.add(operation);
        }
    }

    public Operation randomOperation(){
        return get(random.nextInt(size()-1));
    }

    public Operation getOperationById(UUID id){
        return stream().filter(op->op.getId().equals(id)).findFirst().orElse(null);
    }

    public Set<UUID> operationIds(){
        return stream().map(op->op.getId()).collect(Collectors.toSet());
    }

    public JsonArray toManifest(){
        return stream().map(Operation::toJson)
                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
    }

}
