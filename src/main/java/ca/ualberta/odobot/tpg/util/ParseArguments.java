package ca.ualberta.odobot.tpg.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Scanner;

public class ParseArguments {
	
	private static ParseArguments instance;
	
	
	public static ParseArguments getInstance()
	{
		if (instance != null)
		{
			return instance;
		}
		else {
			instance = new ParseArguments();
			return instance;
		}
	}
	
	public ParseArguments()
	{
		
	}
	
	public HashMap<String, String> readArgumentsToMap( String fileName )
	{
		HashMap<String, String> arguments = new HashMap<String, String>();
		
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
		
		return arguments;
	}
	
	

}
