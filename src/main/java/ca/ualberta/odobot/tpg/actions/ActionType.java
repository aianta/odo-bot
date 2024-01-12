package ca.ualberta.odobot.tpg.actions;

public abstract class ActionType {

    public abstract double[] process(double[] inputs);
    public abstract <T> T copy();
    public abstract boolean mutate(Long newLabel, double programDelete, double programAdd, double programSwap, double programMutate, int maxProgramSize, boolean canWrite);
    public abstract boolean equals(ActionType other);
    //{
    //	return new double[1];
    //}

}