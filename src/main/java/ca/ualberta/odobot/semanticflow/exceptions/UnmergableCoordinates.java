package ca.ualberta.odobot.semanticflow.exceptions;

import ca.ualberta.odobot.semanticflow.statemodel.Coordinate;

public class UnmergableCoordinates extends Exception{

    private Coordinate source;
    private Coordinate target;

    public UnmergableCoordinates(Coordinate source, Coordinate target){
        this.source = source;
        this.target = target;
    }

    public String getMessage(){
        return "Could not merge:\n-------------------\n" + source.toString() + "\n-------------------\nwith\n-------------------\n" + target.toString() + "\n-------------------\n";
    }
}
