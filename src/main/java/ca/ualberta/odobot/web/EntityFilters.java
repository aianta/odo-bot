package ca.ualberta.odobot.web;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import io.vertx.core.json.JsonObject;

/**
 * @author Alexandru Ianta
 *
 * A collection of predicate classes used to filter json representations of entities in timelines.
 */
public class EntityFilters {

    final static String SYMBOL_FIELD = "symbol";
    final static String TERMS_FIELD = "terms";

    /**
     * Returns true if an entity is of a specific symbol and has terms.
     */
    public static class isSymbolAndHasTerms implements Predicate<JsonObject>{
        private String symbol;

        public isSymbolAndHasTerms(String symbol){
            this.symbol = symbol;
        }

        @Override
        public boolean test(JsonObject entity) {
            return entity.getString(SYMBOL_FIELD).equals(this.symbol) && new EntityFilters.hasTerms().test(entity);
        }
    }

    public static class doesNotHaveTerms implements Predicate<JsonObject>{

        @Override
        public boolean test(JsonObject entity) {
            return entity.getJsonArray(TERMS_FIELD).size() == 0;
        }
    }

    /**
     * Returns true for any entity with more than 0 terms.
     */
    public static class hasTerms implements Predicate<JsonObject>{

        @Override
        public boolean test(JsonObject entity) {
            return entity.getJsonArray(TERMS_FIELD).size() > 0;
        }
    }

    /**
     * Returns true if an entity has a symbol belonging to a predefined list and has more than 0 terms.
     */
    public static class hasSymbolAndHasTerms implements Predicate<JsonObject>{
        private List<String> symbols;
        public hasSymbolAndHasTerms(List<String> symbols){
            this.symbols = symbols;
        }

        @Override
        public boolean test(JsonObject entity) {
            return this.symbols.contains(entity.getString(SYMBOL_FIELD)) && new EntityFilters.hasTerms().test(entity);
        }
    }

}
