package ca.ualberta.odobot.tpg.analysis;

import ca.ualberta.odobot.tpg.Instruction;
import ca.ualberta.odobot.tpg.Program;
import ca.ualberta.odobot.tpg.actions.ActionProgram;
import ca.ualberta.odobot.tpg.learners.Learner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class TeamExecutionTrace {

    private static final Logger log = LoggerFactory.getLogger(TeamExecutionTrace.class);

    private double [] inputFeatures;
    List<Learner> learners = new ArrayList<>();

    public TeamExecutionTrace(double [] featureVector){
        this.inputFeatures = featureVector;
    }

    /**
     * Produces a list of feature vector indices that are retrieved during the execution of a series of learners.
     * @return
     */
    public List<Integer> indexedLocations(){

        //Initialize a list to hold the indicies that were accessed in the feature vector
        List<Integer> result = new ArrayList<>();

        //Iterate through our list of learners
        Iterator<Learner> it = learners.iterator();

        while (it.hasNext()){

            Learner curr = it.next();

            result.addAll(processProgram(curr.bidProgram));


            if(!it.hasNext()){
                //If this is the last learner in a trace, it has an action program we must also analyze.
                ActionProgram actionProgram = (ActionProgram)curr.getActionObject().action;
                result.addAll(processProgram(actionProgram.actionProgram));
            }
        }


        return result;

    }


    private List<Integer> processProgram(Program program){

        List<Integer> result = new ArrayList<>();

        program.program.forEach(instruction->{
            //If this is an instruction that indexes the feature vector
            if(instruction.getModeRegister().equals(Instruction.mode2)){
                result.add((int) (instruction.getSourceRegister().getLongValue() % inputFeatures.length) );
            }
        });

        return result;
    }
}
