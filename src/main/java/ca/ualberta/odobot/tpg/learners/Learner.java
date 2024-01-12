package ca.ualberta.odobot.tpg.learners;


import java.util.ArrayList;

import ca.ualberta.odobot.tpg.actions.Action;
import ca.ualberta.odobot.tpg.actions.ActionProgram;
import ca.ualberta.odobot.tpg.actions.ActionType;
import ca.ualberta.odobot.tpg.actions.ActionLabel;
import ca.ualberta.odobot.tpg.Program;



public class Learner
{
    // The number of general purpose registers for the bid program held by all Learners
    public static int REGISTERS = 8;

    // An array representing the general purpose registers for this Learner
    public double[] registers = null;

    // Static variable holding the next ID to be used for a new Learner
    public static long count = 0;

    // Unique ID of this Learner
    public long ID = 0;

    // Time step at which this Learner was generated
    public long birthday = 0;


    public Action action = null;

    // The number of Teams currently referencing this Learner
    public int teamReferenceCount = 0;


    // This Learner's program for calculating a bid based on an input
    public Program bidProgram = null;


    //make a new learner with randomized bid program and randomized action program.
    public Learner( long gtime, long action, int actionProgramRegisterCount, int maxActionProgramSize, int maxBidProgSize )
    {
        // Grab a unique ID and increment the counter
        ID = count++;

        // Today is this Learner's birthday!
        this.birthday = gtime;

        // make a new randomized action program
        //this.action = new Action(action, actionProgramRegisterCount, maxActionProgramSize);

        if (actionProgramRegisterCount == -1)
        {
            ActionLabel act = new ActionLabel(action);
            this.action = new Action<ActionLabel>(act);
        }
        else
        {
            ActionProgram act = new ActionProgram(actionProgramRegisterCount, maxActionProgramSize);
            this.action = new Action<ActionProgram>(act);
        }


        // This Learner doesn't belong to any Teams yet
        this.teamReferenceCount = 0;

        //make a new randomized bid program
        this.bidProgram = new Program(REGISTERS, maxBidProgSize, true);
    }

    //empty constructor for Kryo
    public Learner()
    {

    }

    // Create a new uniquely ID'd Learner which is otherwise a copy of another Learner
    public Learner( long gtime, Learner other )
    {
        // Grab a unique ID and increment the counter
        ID = count++;

        // Today is this Learner's birthday!
        this.birthday = gtime;

        // Copy the other Learner's action
        this.action = new Action(other.action);

        // This Learner doesn't belong to any Teams yet
        this.teamReferenceCount = 0;

        // Initialize a new set of general purpose registers
        registers = new double[REGISTERS];

        // Copy the other Learner's program
        this.bidProgram = new Program(other.bidProgram);

        // If the Learner we're copying is holding a pointer to a Team,
        // we have to increment it here because this new Learner is
        // also pointing to that Team.
        if( !this.action.isAtomic() )
            this.action.team.increaseReferences();
    }

    // Calculate a bird from the feature set
    public double bid( double[] inputFeatures )
    {
        // Make sure all the general purpose registers are set to 0.
        // If you want to add simple memory, comment this for loop out!
        //for( int i=0; i < registers.length; i++ )
        //	registers[i] = 0;

        // Use the Learner's program to generate a bid and return it.
        // Uses the formula: bid = 1/(1+e^x), where x is the program output.
        // Throw the formula into Wolfram Alpha if you don't know what it looks like.
        return 1 / ( 1 + Math.exp( -run( inputFeatures )[0] ) );
    }

    // Run the program on the given input feature set and return a pre-bid output
    protected double[] run( double[] inputFeatures )
    {
        return bidProgram.run(inputFeatures);
    }

    public int size()
    {
        return bidProgram.size();
    }

    public long getID()
    {
        return ID;
    }

    public Action getActionObject()
    {
        return action;
    }

    public long getBirthday()
    {
        return birthday;
    }



    // Perform various mutation operations to this Learner's program
    public boolean mutateProgram( double programDelete, double programAdd, double programSwap, double programMutate, int maxProgramSize )
    {
        return bidProgram.mutateProgram(programDelete, programAdd, programSwap, programMutate, maxProgramSize, true);
    }

    // Change this Learner's current action to a new one
    public boolean mutateAction( Action action )
    {
        // Store a copy of the current action
        Action a = this.action;

        // Store the new action in this Learner
        this.action = action;

        // If we're placing a reference to a Team, make sure we dereference it
        if( !a.isAtomic() )
            a.team.decreaseReferences();

        // If the previous action and the new action are different, return true
        return !a.equals(action);
    }

    // Increase the number of references to this Learner and return the new value
    public int increaseReferences()
    {
        return ++teamReferenceCount;
    }

    // Decrease the number of references to this Learner and return the new value
    public int decreaseReferences()
    {
        return --teamReferenceCount;
    }

    // Return the number of references to this Team
    public int getReferences()
    {
        return teamReferenceCount;
    }


    // Override of the Object.equals(Object) method.
    public boolean equals(Object object)
    {
        if( !(object instanceof Learner) )
            return false;
        if( object == this )
            return true;

        Learner other = (Learner)object;
        return ID == other.ID;
    }

    // Use the Long class technique for turning long values
    // into hash values that fit in an int.
    public int hashCode()
    {
        return (int)(ID^(ID>>>32));
    }
}