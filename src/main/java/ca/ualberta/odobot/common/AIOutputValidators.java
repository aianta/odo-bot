package ca.ualberta.odobot.common;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.function.Predicate;

public class AIOutputValidators {

    private static final Logger log = LoggerFactory.getLogger(AIOutputValidators.class);

    public static Predicate<String> isValidJsonArray(){
        return (output)->{
            try{
                JsonArray array = new JsonArray(output);
                return true;
            }catch (DecodeException e){
                return false;
            }
        };
    }

    public static Predicate<String> isNumber = (input)->{
        log.info("Validating input: {}", input);
        try{
            Integer.parseInt(input);
            log.info("Input is fine");
            return true;
        }catch (NumberFormatException e){
            log.info("Input is not fine");
            return false;
        }
    };


    /**
     * Returns true if the objects contains a json array in the specified field whose values are integers
     * in the specified range.
     *
     * @param fieldName the field expected to contain a json array of integers.
     * @param start start of integer range
     * @param end end of integer range
     * @return
     */
    public static Predicate<JsonObject> arrayFieldValuesAreIntegersInRange(String fieldName, int start, int end){
        return (input)->{
            try{
                JsonArray array = input.getJsonArray(fieldName);

                try{

                    for (Object item: array){
                        Integer value = (Integer)item;
                        if(value < start || value > end){
                            return false;
                        }
                    }
                }catch (ClassCastException e){
                    return false;
                }

                return true;

            }catch (DecodeException e){
                return false;
            }
        };
    }


    public static <T> Predicate<String> allJsonArrayValuesMatchPredicate(Predicate<T> predicate, Class<T> castingClass){
        return  (input)->{
            try{
                JsonArray inputArray = new JsonArray(input);
                return inputArray.stream()
                        .map(castingClass::cast)
                        .allMatch(predicate);
            }catch (DecodeException e){
                return false;
            }
        };
    }

    /**
     * Assumes the string represents a valid JsonArray containing JsonObjects
     * and verifies that all objects contain the provided keys.
     * @param keys
     * @return
     */
    public static Predicate<String> allJsonArrayObjectsHaveKeys(String ...keys){
        return allJsonArrayValuesMatchPredicate(hasKeys(keys), JsonObject.class);
    }

    public static Predicate<JsonObject> hasKeys(String ...keys){
        return (object)->{
            return Arrays.stream(keys)
                    .allMatch(object::containsKey);

        };
    }

}
