package ca.ualberta.odobot.semanticflow.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/**
 * Semantic artifact that arises from a series of input changes.
 *
 * We use the data entry class to assemble a meaningful view of a set of input changes. For example,
 * an INPUT_CHANGE event is triggered every time the user enters a character into a text field.
 * This class allows us to work with the final value of entered data.
 */
public class DataEntry extends ArrayList<InputChange> {

    private static final Logger log = LoggerFactory.getLogger(DataEntry.class);

    public String getEnteredData(){
        return get(size()-1).getValue();
    }

}
