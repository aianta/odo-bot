package ca.ualberta.odobot.tpg.teams;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ca.ualberta.odobot.tpg.actions.Action;
import ca.ualberta.odobot.tpg.learners.Learner;
import ca.ualberta.odobot.tpg.util.OpenDouble;
import ca.ualberta.odobot.tpg.util.Pair;

public class Team implements Comparable<Team>
{
    // Static variable holding the next ID to be used for a new Team
    public static long count = 0;

    // Unique ID of this Team
    public long ID = 0;

    // Time step at which this Team was generated
    public long birthday = 0;

    // An array list of Learners attached to this Team
    public ArrayList<Learner> learners = new ArrayList<Learner>();

    // A mapping of String -> Double, where an input session is named by the String and the score is stored as a Double
    public HashMap<String, Double> outcomes = new HashMap<String, Double>();

    // This key is used for sorting during Pareto calculations. Pareto calculation has been removed
    // from this version of TPG, but will be added as a support method at a later date.
    public double key = 0;

    // The number of Learners currently referencing this Team
    public int learnerReferenceCount = 0;

    //for Dota, we want to store information such as last hits and denies, in addition to it's overall fitness.
    public HashMap<String, Double> fitnessDetails = new HashMap<String, Double>();

    //Hold the fitness for each task over time, for multitask things.
    //It's different from fitness details though okay there's no way I could use those for
    // the same thing ever
    protected ArrayList<Pair<String, Double>> taskFitness = new ArrayList<Pair<String, Double>>();


    // Reconstruct a Team from primary data. WE'VE CREATED A MONSTER!
    public Team( long ID, long birthday, double key, ArrayList<Learner> learners, HashMap<String, Double> outcomes )
    {
        this.ID = ID;
        this.birthday = birthday;
        this.key = key;
        this.learners = learners;
        this.learnerReferenceCount = 0;
    }
    public Team()
    {

    }
    // Create a blank Team with the given ID, Birthday, and References.
    public Team(long ID, long birthday, int references)
    {
        this.ID = ID;
        this.birthday = birthday;
        this.learnerReferenceCount = references;
    }

    // Create a new Team and set its creation time
    public Team( long birthday )
    {
        this.birthday = birthday;
        this.learnerReferenceCount = 0;
        ID = count++;
    }

    // The size of a Team is equal to the number of Learners attached to it
    public int size()
    {
        return learners.size();
    }

    public long getBirthday()
    {
        return birthday;
    }

    public long getID()
    {
        return ID;
    }

    public double getKey()
    {
        return key;
    }

    public void setKey( double key )
    {
        this.key = key;
    }

    // Add a Leaner to this Team. If the Learner is already a member, skip it and return false.
    public boolean addLearner( Learner learner )
    {
        // If we already have this learner stored, don't add it
        if( learners.contains(learner) )
            return false;

        // It's not already in there, so add it
        learners.add(learner);

        // Increase the reference count of this Learner
        learner.increaseReferences();

        // Add successful; return true
        return true;
    }

    // If a Learner is on this Team, remove it. Otherwise throw an error
    // because we never even attempt to remove a Learner that isn't attached.
    // This is basically a hold-over from a previous version and doesn't need to
    // work this way. C'est la vie.
    public void removeLearner( Learner learner )
    {
        // If the requested Learner doesn't exist on this Team, something went wrong
        if( !learners.contains(learner) )
            throw new RuntimeException("The program tried to remove a Learner that does not exist.");

        // Remove the Learner from the Team
        learners.remove( learner );

        // Decrement the References of the Learner
        learner.decreaseReferences();
    }

    // Add this Team's Learners to a given list
    public ArrayList<Learner> getLearners()
    {
        return learners;
    }

    // Get the outcome for a data input session label. If we haven't done any learning on
    // that label, return false.
    public boolean getOutcome( String name, OpenDouble out )
    {
        // If we haven't tried this activity, return false.
        if( !outcomes.containsKey(name) )
            return false;

        // Otherwise get the score and store it in the out object
        out.setValue( outcomes.get(name) );

        // Return true if we returned a value in the out object
        return true;
    }

    // This Team is receiving a reward value. Store it in the outcomes map.
    // This version can't perform the same labelled activity more than once.
    // Change the map activity (or incoming label) to do something different.
    public void setOutcome( String name, Double out )
    {
        // We can't do the same activity more than once. If we do, throw an error.
        //if( outcomes.containsKey(name) )
        //	throw new RuntimeException("Tried to add a duplicate activity label to an outcomes map.");

        // If we haven't done this before, store it.
        outcomes.put(name, out);

    }

    // Remove a score from a Team's outcomes map. If it doesn't exist, that's bad. Something is very broken.
    public void deleteOutcome( String name )
    {
        // If we didn't do this activity, throw an error. This is a very bad thing. The code needs to be fixed.
        if( !outcomes.containsKey(name) )
            throw new RuntimeException("Tried to delete an activity label that does not exist from an outcomes map.");

        // If it does exist, remove it.
        outcomes.remove(name);
    }

    // Return the number of scores this Team has stored so far.
    public int numOutcomes()
    {
        return outcomes.size();
    }

    // Retrieve all outcomes along with the corresponding points
    public void outcomes( ArrayList<String> names, ArrayList<Double> scores )
    {
        // For every name->score pair in the map, store the name and score in a list.
        // The lists are updated in-place in memory and don't need to be returned.
        for( String name : outcomes.keySet() )
        {
            names.add( name );
            scores.add( outcomes.get(name) );
        }
    }


    // Provide this Team with an input state set and return an action
    public double[] getAction( HashSet<Team> visited, double[] state )
    {
        Learner bestLearner = null;
        double maxBid = 0;
        double nextBid = 0;

        // Add this Team to the visited set
        visited.add(this);

        // Create an integer for iteration
        int i = 0;

        // Get the first bid from the Learners based on their Action object
        for( i=0; i < learners.size(); i++ )
        {
            // Get the next Learner from the list
            bestLearner = learners.get(i);

            // If this Learner's Action is a Team and we've visited that Team before, skip this Learner
            if( !bestLearner.getActionObject().isAtomic() && visited.contains(bestLearner.getActionObject().team) )
                continue;

            // Otherwise we can get the Learner's bid
            maxBid = learners.get(i).bid( state );

            // We've found our starting Learner, so break
            break;
        }

        // Query the rest of the Learners to get the highest bid from the Learner pool
        for( i += 1 ; i < learners.size(); i++ )
        {
            // If this Learner's Action is a Team and we've visited that Team before, skip this Learner
            if( !learners.get(i).getActionObject().isAtomic() && visited.contains(learners.get(i).getActionObject().team) )
                continue;

            // Otherwise get the bid from this Learner
            nextBid = learners.get(i).bid( state );

            // If this bid is higher than the previous highest bid, store it and the Learner
            if( nextBid > maxBid )
            {
                maxBid = nextBid;
                bestLearner = learners.get(i);
            }
        }

        //run the best Learner's action program and get the resulting action register[0].
        //bestLearner.getActionObject().runActionProgram(visited,state);
        //return bestLearner.getActionObject().getActionRegisters(visited,state);


        // Return the action of the best Learner
        return bestLearner.getActionObject().getAction(visited,state);
    }

    public double[] getAction( HashSet<Team> visited, List<Learner> visitedLearners, double[] state )
    {
        Learner bestLearner = null;
        double maxBid = 0;
        double nextBid = 0;

        // Add this Team to the visited set
        visited.add(this);

        // Create an integer for iteration
        int i = 0;

        // Get the first bid from the Learners based on their Action object
        for( i=0; i < learners.size(); i++ )
        {
            // Get the next Learner from the list
            bestLearner = learners.get(i);

            // If this Learner's Action is a Team and we've visited that Team before, skip this Learner
            if( !bestLearner.getActionObject().isAtomic() && visited.contains(bestLearner.getActionObject().team) )
                continue;

            // Otherwise we can get the Learner's bid
            maxBid = learners.get(i).bid( state );

            // We've found our starting Learner, so break
            break;
        }

        // Query the rest of the Learners to get the highest bid from the Learner pool
        for( i += 1 ; i < learners.size(); i++ )
        {
            // If this Learner's Action is a Team and we've visited that Team before, skip this Learner
            if( !learners.get(i).getActionObject().isAtomic() && visited.contains(learners.get(i).getActionObject().team) )
                continue;

            // Otherwise get the bid from this Learner
            nextBid = learners.get(i).bid( state );

            // If this bid is higher than the previous highest bid, store it and the Learner
            if( nextBid > maxBid )
            {
                maxBid = nextBid;
                bestLearner = learners.get(i);
            }
        }

        //run the best Learner's action program and get the resulting action register[0].
        //bestLearner.getActionObject().runActionProgram(visited,state);
        //return bestLearner.getActionObject().getActionRegisters(visited,state);

        visitedLearners.add(bestLearner);

        // Return the action of the best Learner
        return bestLearner.getActionObject().getAction(visited, visitedLearners, state);
    }

    // Provide this Team with an input state set and return an action
    public double[] getAction( HashSet<Team> visited, double[] state, ArrayList<String> seq)
    {
        Learner bestLearner = null;
        double maxBid = 0;
        double nextBid = 0;

        // Add this Team to the visited set
        visited.add(this);
        seq.add("T:" + ID);
        // Create an integer for iteration
        int i = 0;

        // Get the first bid from the Learners based on their Action object
        for( i=0; i < learners.size(); i++ )
        {
            // Get the next Learner from the list
            bestLearner = learners.get(i);

            // If this Learner's Action is a Team and we've visited that Team before, skip this Learner
            if( !bestLearner.getActionObject().isAtomic() && visited.contains(bestLearner.getActionObject().team) )
                continue;

            // Otherwise we can get the Learner's bid
            maxBid = learners.get(i).bid( state );

            // We've found our starting Learner, so break
            break;
        }

        // Query the rest of the Learners to get the highest bid from the Learner pool
        for( i += 1 ; i < learners.size(); i++ )
        {
            // If this Learner's Action is a Team and we've visited that Team before, skip this Learner
            if( !learners.get(i).getActionObject().isAtomic() && visited.contains(learners.get(i).getActionObject().team) )
                continue;

            // Otherwise get the bid from this Learner
            nextBid = learners.get(i).bid( state );

            // If this bid is higher than the previous highest bid, store it and the Learner
            if( nextBid > maxBid )
            {
                maxBid = nextBid;
                bestLearner = learners.get(i);
            }
        }

        //run the best Learner's action program and get the resulting action register[0].
        //bestLearner.getActionObject().runActionProgram(visited,state);
        //return bestLearner.getActionObject().getActionRegisters(visited,state);


        // Return the action of the best Learner
        seq.add("L:" + bestLearner.ID);
        return bestLearner.getActionObject().getAction(visited,state, seq);
    }


    // This Team is being deleted. Make sure Learner references are decreased before it's gone!
    public void erase()
    {
        // For each Learner attached to this Team, reduce their number of references by 1.
        for( Learner learner : learners )
            learner.decreaseReferences();
    }

    // Find every team attached to this team and return the complete set
    public void findAllTeams(Set<Team> found)
    {
        // If we've already found this team, skip it and return.
        if(found.contains(this))
            return;

        // Otherwise add this team to the found set.
        found.add(this);

        // Check the action of every Learner. If there is a Team reference,
        // recursively call this method on it.
        for(Learner learner: learners)
        {
            // Hold the action in a new variable for convenience
            Action action = learner.getActionObject();

            // If the action is not atomic, it's a team reference, so
            // make the resursive call here.
            if(!action.isAtomic())
                action.team.findAllTeams(found);
        }

        // Explicit return when the process is complete.
        return;
    }

    // Increase the number of references to this Team and return the new value
    public int increaseReferences()
    {
        return ++learnerReferenceCount;
    }

    // Decrease the number of references to this Team and return the new value
    public int decreaseReferences()
    {
        return --learnerReferenceCount;
    }

    // Return the number of references to this Learner
    public int getReferences()
    {
        return learnerReferenceCount;
    }

    // Return a string representation of this Team
    public String toString()
    {
        String output = "ID " + ID + " Size " + learners.size();
        output += " Birthday " + birthday;

        for( Learner learner : learners )
        {
            output += " " + learner;
        }

        return output;
    }

    // Return a string representation of this Team,
    // designed to be stored in a file.
    public String storageOutput()
    {
        // Provide the basic Team information as the
        // first line, followed by a single empty line
        String out = ID + " " + birthday + " " + learnerReferenceCount + "\n\n";

        // For each Learner in this Team's learner list,
        // store a Learner ID on its own line.
        for( Learner L: learners )
            out += L.getID() + "\n";

        // Return the representative string
        return out;
    }

    // Compare two Teams by key.
    public int compareTo( Team other )
    {
        double check = other.getKey() - getKey();

        if( check == 0.0 )
            return 0;
        else if( check < 0.0 )
            return 1;
        else
            return -1;
    }

    // Originally did a partial sort, but it's less complicated in Java to do
    // a full sort because breaking down lists in Java takes too long.
    public static void sortListByKey( List<Team> list )
    {
        Collections.sort(list, new Comparator<Team>()
        {
            @Override
            public int compare(Team o1, Team o2)
            {
                double check = o2.getKey() - o1.getKey();

                if( check == 0.0 )
                    return 0;
                else if( check < 0.0 )
                    return 1;
                else
                    return -1;
            }
        } );
    }

    // Override of the Object.equals(Object) method.
    public boolean equals(Object object)
    {
        if( !(object instanceof Team) )
            return false;
        if( object == this )
            return true;

        Team other = (Team)object;
        return ID == other.ID;
    }

    // Use the Long class technique for turning long values
    // into hash values that fit in an int.
    public int hashCode()
    {
        return (int)(ID^(ID>>>32));
    }


    //this little number gets me the outcome
    //for a particular task without the open double thing
    public Double getOutcomeByKey(String key)
    {
        return outcomes.get(key);
    }

    public void incrementFitnessDetail(String detail, double value)
    {
        //if the fitness details hashmap already has this
        //key in it, then we want to add to that value.
        if (fitnessDetails.containsKey(detail))
        {
            double temp = fitnessDetails.get(detail);
            temp = temp + value;
            fitnessDetails.put(detail, temp);
        }
        else {
            addFitnessDetail(detail, value);
        }
    }

    public void addFitnessDetail(String detail, double value)
    {
        fitnessDetails.put(detail, value);
    }

    public double getFitnessDetail(String detail)
    {
        return fitnessDetails.get(detail);
    }
    public HashMap<String, Double> getFitnessDetailS()
    {
        return fitnessDetails;
    }

    public ArrayList<String> getFitnessDetailStringsAsArrayList()
    {
        ArrayList<String> arraylist = new ArrayList<String>();
        for (String s : fitnessDetails.keySet())
        {
            arraylist.add(s);
        }
        return arraylist;
    }

    public HashMap<String, Double> getOutcomeMap()
    {
        return outcomes;
    }
    public ArrayList<Pair<String, Double>> getTaskFitness()
    {
        return this.taskFitness;
    }

    public Double getTaskFitness(String t)
    {
        Double score = 0.0;
        for (int i = 0; i < taskFitness.size(); i++)
        {
            if (taskFitness.get(i).getFirst().equals(t))
            {
                score = taskFitness.get(i).getSecond();
            }
        }
        return score;
    }

    //checks for the existence of a string in the task fitness arraylist
    //returns true if the string is there, false if not.
    public boolean checkTaskFitness(String s)
    {
        boolean flag = false;
        for (int i = 0; i < taskFitness.size(); i++)
        {
            if (taskFitness.get(i).getFirst().equals(s))
            {
                flag = true;
            }
        }

        return flag;
    }

    //counts how many times a task name appears in the fitness
    //arraylist. it should only ever be 1 or 0.
    //if there's 2 or more, we have a problem.
    public int countTaskFitness(String s)
    {
        int count = 0;

        for (int i = 0; i < taskFitness.size(); i++)
        {
            if (taskFitness.get(i).getFirst().equals(s))
            {
                count++;
            }
        }
        return count;
    }

    //adds an entry to the end of the taskfitness arraylist
    public void addTaskFitness(String s, Double score)
    {
        //check if this string is already in taskFitness
        if (checkTaskFitness(s) == false)
        {
            Pair<String, Double> newEntry = new Pair<String,Double>(s, score);
            taskFitness.add(newEntry);
        }
        else
        {
            updateTaskFitness(s, score);
        }

    }

    //if the team already had an entry for this task,
    //this method updates that score and puts it at the end of the arraylist I guess idk
    public void updateTaskFitness(String s, Double newScore)
    {
        //check that the given string only appears once
        int count = countTaskFitness(s);
        if (count != 1)
        {
            System.out.println("Houston, we have a problem.");
        }


        int index = 0;
        for (int i = 0; i < taskFitness.size(); i++)
        {
            if (taskFitness.get(i).getFirst().equals(s))
            {
                index = i;
            }
        }
        Pair<String, Double> oldEntry = taskFitness.remove(index);
        Pair<String, Double> newEntry = new Pair<String,Double>(s, newScore);
        taskFitness.add(newEntry);
    }

    public Double getLastFitness()
    {
        int size = taskFitness.size() - 1;
        Double score = taskFitness.get(size).getSecond();
        return score;
    }
}