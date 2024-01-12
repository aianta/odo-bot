package ca.ualberta.odobot.tpg.actions;


public class ActionLabel extends ActionType{

    public Long actionLabel;

    public ActionLabel(long label)
    {
        this.actionLabel = label;
    }
    public ActionLabel()
    {

    }

    @Override
    public double[] process(double[] inputs) {
        double[] arr = {actionLabel};
        return arr;
    }
    @Override
    public ActionLabel copy() {
        return new ActionLabel(this.actionLabel);
    }
    @Override
    public boolean mutate(Long newLabel, double programDelete, double programAdd, double programSwap,
                          double programMutate, int maxProgramSize, boolean canWrite) {

        if(newLabel == -1L){
            throw new RuntimeException("new action label is -1!");
        }

        this.actionLabel = newLabel;
        return true;

    }

    @Override
    public boolean equals(ActionType other) {

        if (((ActionLabel)other).actionLabel.equals(this.actionLabel))
            return true;

        return false;
    }

}
