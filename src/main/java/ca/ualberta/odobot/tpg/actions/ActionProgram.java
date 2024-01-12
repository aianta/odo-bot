package ca.ualberta.odobot.tpg.actions;


import java.util.Arrays;

import ca.ualberta.odobot.tpg.Program;

public class ActionProgram extends ActionType{

    // This action's program for coming up with a real valued action
    public Program actionProgram = null;

    public ActionProgram(int actionProgramRegisterCount, int maxActionProgramSize)
    {
        this.actionProgram = new Program(actionProgramRegisterCount, maxActionProgramSize, false);
    }

    public ActionProgram(Program p)
    {
        this.actionProgram = p;
    }

    public ActionProgram()
    {

    }

    @Override
    public double[] process(double[] inputFeatures) {

        double[] output = actionProgram.run(inputFeatures);
        //output = Arrays.copyOf(output, output.length + 1);
        //output[output.length - 1] = action.floatValue();
        return output;
    }

    @Override
    public ActionProgram copy() {
        return new ActionProgram(new Program(this.actionProgram));
    }

    @Override
    public boolean mutate(Long newLabel, double programDelete, double programAdd, double programSwap,
                          double programMutate, int maxProgramSize, boolean canWrite) {

        return actionProgram.mutateProgram(programDelete, programAdd, programSwap, programMutate, maxProgramSize, canWrite);

    }


    @Override
    public boolean equals(ActionType other) {

        return actionProgram.equals(((ActionProgram) other).actionProgram);

    }

}
