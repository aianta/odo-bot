package ca.ualberta.odobot.semanticflow;

import ca.ualberta.odobot.semanticflow.statemodel.Coordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class State {
    private static final Logger log = LoggerFactory.getLogger(State.class);
    private UUID id;
    private Coordinate root;
    private Set<Coordinate> members = new HashSet<>();



    public Optional<Coordinate> getCoordinate(String xpath){
        return members.stream().filter(coordinate -> coordinate.xpath.equals(xpath)).findFirst();
    }

}
