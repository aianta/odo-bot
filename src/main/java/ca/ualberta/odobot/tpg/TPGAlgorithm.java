package ca.ualberta.odobot.tpg;


import java.util.*;

import com.esotericsoftware.kryo.kryo5.DefaultSerializer;

import ca.ualberta.odobot.tpg.TPGLearn;

import ca.ualberta.odobot.tpg.util.serializers.TPGAlgorithmSerializer;
import io.vertx.core.json.JsonObject;

import java.io.*;

@DefaultSerializer(TPGAlgorithmSerializer.class)
public class TPGAlgorithm
{
    public static TPGAlgorithm instance;

    // A map for holding arguments from the parameters file
    public HashMap<String, String> arguments = null;

    // A variable for holding a static Random Number Generator
    public static Random RNG = null;

    // A variable for holding a Memory Model
    public static MemoryModel memory = null;

    // TPG Framework Objects
    protected TPGLearn tpgLearn = null;
    protected TPGPlay tpgPlay = null;

    public static TPGAlgorithm getInstance()
    {
        return instance;
    }


    public static TPGAlgorithm getInstance( JsonObject arguments, String modelFile, String type)
    {
        if (instance != null)
        {
            return instance;
        }
        else {
            instance = new TPGAlgorithm(arguments, modelFile, type);
            return instance;
        }

    }


    public static TPGAlgorithm getInstance(TPGAlgorithm reconstructed)
    {
        if (instance != null)
        {
            return instance;
        }

        else
        {
            instance = reconstructed;
            return instance;
        }

    }

    // Create a new TPGAlgorithm in Learn or Play mode
    public TPGAlgorithm( JsonObject arguments, String modelFile, String type )
    {
        if( type.equals("learn") )
        {
            System.out.println("Starting TPG in Learning Mode.");
            startLearning( arguments );
        }
        else if( type.equals("play") )
        {
            System.out.println("Starting TPG in Play Mode.");
            throw new RuntimeException("Play mode is not supported!");
            //startPlaying( argumentsFile, modelFile );
        }
        else
            throw new RuntimeException("Uh, we had a slight input parameters malfunction, but uh... everything's perfectly all right now. We're fine. We're all fine here now, thank you. How are you?");
    }
    public TPGAlgorithm()
    {

    }

    public TPGAlgorithm(HashMap<String, String> arguments, Random RNG, MemoryModel memory, TPGLearn tpgLearn)
    {
        this.arguments = arguments;
        this.RNG= RNG;
        this.memory = memory;
        this.tpgLearn = tpgLearn;
    }

    // Start a Learn session
    public void startLearning(JsonObject args)
    {
        // Create new data structures for storage
        arguments = new HashMap<String, String>();

        // Set the procedure type to all before checking it
        arguments.put("procedureType", "all");

        // Get the arguments
        //readArgumentsToMap( argumentsFile );
        args.forEach((entry)->{
            if(entry.getValue() instanceof String){
                arguments.put(entry.getKey(), (String)entry.getValue());
            }
        });


        // Set the seed for the RNG
        RNG = new Random( Integer.parseInt(arguments.get("seed")) );
        if( Integer.valueOf(arguments.get("seed")) == 0 )
            RNG = new Random( System.currentTimeMillis() );

        // Set up the Memory Model to be 100x8
        memory = new MemoryModel(100, 8);

        //memory.printModel();
        // Create a new TPGLearn object to start the learning process
        tpgLearn = new TPGLearn(arguments);
    }

    // Start a Play session
//    public void startPlaying( String argumentsFile, String modelFile )
//    {
//        // Create new data structures for storage
//        arguments = new HashMap<String, String>();
//
//        // Set the procedure type to all before checking it
//        arguments.put("procedureType", "all");
//
//        // Get the arguments
//        readArgumentsToMap( argumentsFile );
//
//        // Set up the Memory Model to be 100x8
//        memory = new MemoryModel(100, 8);
//
//        // Set the seed for the RNG
//        RNG = new Random( Integer.parseInt(arguments.get("seed")) );
//        if( Integer.valueOf(arguments.get("seed")) < 0 )
//            RNG = new Random( System.currentTimeMillis() );
//
//        // Creates a new TPGPlay object. Does not need arguments or RNG.
//        tpgPlay = new TPGPlay(modelFile);
//    }

    public HashMap<String, String> getArguments()
    {
        return arguments;
    }
    // Get the TPGLearn object. This returns null if TPGAlgorithm is in Play mode.
    public TPGLearn getTPGLearn()
    {
        return tpgLearn;
    }

    // Get the TPGPlay object. This returns null if TPGAlgorithm is in Learn mode.
    public TPGPlay getTPGPlay()
    {
        return tpgPlay;
    }
    public Random getRNG()
    {
        return RNG;
    }
    public MemoryModel getMemory()
    {
        return memory;
    }

    // Read the arguments from a file and store them in an arguments map
    public void readArgumentsToMap( String fileName )
    {
        // Create a variable for holding a Scanner
        Scanner argumentsInput = null;

        try
        {
            // Create a new scanner for scanning the file
            argumentsInput = new Scanner( new File(fileName) );

            // Create a variable for holding String arrays
            String line[] = null;

            // While there are more arguments to be read in...
            while( argumentsInput.hasNextLine() )
            {
                // Read in the next line from the file and split it using the equals sign
                line = argumentsInput.nextLine().split("=");

                // Save the line in the arguments map as argName->argValue
                arguments.put(line[0], line[1]);
            }

            // Close the scanner when we're done
            argumentsInput.close();
        }
        catch( FileNotFoundException e )
        {
            throw new RuntimeException("The arguments file name provided does not exist.");
        }
    }
    public void printProbabilities()
    {
        memory.printModel();
    }

    public long getRootTeamCount()
    {
        return tpgLearn.getRootTeams().get(0).count;
    }

    public long getLearnerCount()
    {
        return tpgLearn.getRootTeams().get(0).getLearners().get(0).count;
    }

    public int getRegisterCount()
    {
        return tpgLearn.getRootTeams().get(0).getLearners().get(0).REGISTERS;
    }

}