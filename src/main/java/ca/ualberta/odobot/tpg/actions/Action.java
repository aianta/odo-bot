package ca.ualberta.odobot.tpg.actions;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;


import ca.ualberta.odobot.tpg.Program;
import ca.ualberta.odobot.tpg.learners.Learner;
import ca.ualberta.odobot.tpg.teams.Team;


public class Action<T extends ActionType>
{
    // This action's program for coming up with a real valued action
    //public Program actionProgram = null;
    public T action = null;
    //public ActionType action = null;
    public Team team = null;


    // Create a new Action object which holds a Team.
    public Action( Team team )
    {
        this.team = team;
    }

    public Action(T action)
    {
        this.action = action;
    }

    //make a copy of an action.
    public Action(Action<T> other)
    {
        //If the other action has a team reference:
        if (!other.isAtomic())
            this.team = other.team;

        this.action = other.action.copy();

        //if the action we're copying has an atomic action, meaning an action program
//		if (other.isAtomic())
//		{
//			this.action = other.action.copy();
//
//		}
        //else if the action we're copying has a team then just make the thing the team.
//		else
//		{
//			this.team = other.team;
//		}

        //then we make a copy of that program.
        //this.actionProgram = new Program(other.actionProgram);
    }

    //empty constructor for Kryo.
    public Action()
    {

    }

    public double[] getAction(HashSet<Team> visited, List<Learner> visitedLearners, double[] inputFeatures )
    {
        // If we are not storing an atomic action, then this action holds a Team.
        // Use the provided feature set to generate an action and return it.
        //instead of this thing we are going to run an action program! yay!
        //if this action is a team pointer:
        if (team != null)
        {
            return team.getAction(visited, visitedLearners, inputFeatures);
        }

        //else we're going to run the action program
        else
        {
            return action.process(inputFeatures);

        }
    }

    // Retrieve an action from this object
    public double[] getAction( HashSet<Team> visited, double[] inputFeatures )
    {
        // If we are not storing an atomic action, then this action holds a Team.
        // Use the provided feature set to generate an action and return it.
        //instead of this thing we are going to run an action program! yay!
        //if this action is a team pointer:
        if (team != null)
        {
            return team.getAction(visited, inputFeatures);
        }

        //else we're going to run the action program
        else
        {
            return action.process(inputFeatures);

        }
    }

    // Retrieve an action from this object
    public double[] getAction( HashSet<Team> visited, double[] inputFeatures, ArrayList<String> seq)
    {
        // If we are not storing an atomic action, then this action holds a Team.
        // Use the provided feature set to generate an action and return it.
        //instead of this thing we are going to run an action program! yay!
        //if this action is a team pointer:
        if (action == null)
        {
            return team.getAction(visited, inputFeatures, seq);
        }

        //else we're going to run the action program
        else
        {
            return action.process(inputFeatures);

        }
    }


    // Returns true if this action is atomic.
    public boolean isAtomic()
    {
        return team == null;
    }

    public boolean mutate(Team team)
    {
        this.team = team;
        //Because we check that an action is atomic based on
        // the team being null or not, we don't have to
        // delete the action. it can just chill.
        //this.action = null;
        return true;
    }

    public boolean mutate(T action)
    {
        this.team = null;
        this.action = action;
        return true;
    }

    public boolean mutate(Long newLabel, double programDelete, double programAdd, double programSwap, double programMutate, int maxProgramSize, boolean canWrite)
    {
        this.team = null;
        action.mutate(newLabel, programDelete, programAdd, programSwap, programMutate, maxProgramSize, canWrite);
        return true;
    }


    public boolean equals(Action<T> other )
    {
        // If the teams' IDs match, then return true
//		if( action == null && other.action == null && team.getID() == other.team.getID() )
//			return true;


        if( team == null && other.team == null)
        {
            if (action.equals(other.action))
                return true;
            return false;
        }


        //if( team == null || other.team == null)
        //{
        //	return false;
        //}

        else if (team != null && other.team != null && team.getID() == other.team.getID())
        {
            if( action.equals(other.action))
                return true;
            return false;
        }

        // If the atomic actions are the same, then return true
        //this needs work tbh idk how to compare these two bad boys


        // Otherwise return false
        return false;
    }


    public String toString()
    {
        return "[" + ( isAtomic() ? action.toString() : "T" + team.getID()) + "]";
    }
}
