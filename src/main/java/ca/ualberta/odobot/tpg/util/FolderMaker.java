package ca.ualberta.odobot.tpg.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;

public class FolderMaker {
	
	public FolderMaker()
	{
		
	}
	
	public static void makeFolders(HashMap<String, String> runArguments)
	{
		//Get the current date.
		DateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy-hh-mm-ss");
		Date currentDate = new Date();

		//Put the unique date string in the runArguments.
		runArguments.put("uniqueFolder", dateFormat.format(currentDate));

		String date = runArguments.get("uniqueFolder");

		//destination folder ends in a /
		String destFolder = runArguments.get("destinationFolder");

		String rootFolder = destFolder + date + "/";
		String resultsFolder = rootFolder + "results/";
		String cumulativeFolder = rootFolder;
		String stateFolder = rootFolder + "states/";
		String teamsFolder = rootFolder + "best_teams/";

		runArguments.put("resultsFolder" , resultsFolder);
		runArguments.put("cumulativeFolder" , cumulativeFolder);
		runArguments.put("stateFolder" , stateFolder);
		runArguments.put("teamsFolder" , teamsFolder);


		// If the appropriate directory doesn't exist, create it.
		if(!Files.isDirectory(Paths.get(resultsFolder)))
			new File(resultsFolder).mkdirs();
		if(!Files.isDirectory(Paths.get(cumulativeFolder)))
			new File(cumulativeFolder).mkdirs();
		if(!Files.isDirectory(Paths.get(stateFolder)))
			new File(stateFolder).mkdirs();
		if(!Files.isDirectory(Paths.get(teamsFolder)))
			new File(teamsFolder).mkdirs();

	}

	public static void makeFolders(HashMap<String, String> runArguments, long epoch)	
	{

		//destination folder ends in a /
		String destFolder = runArguments.get("loadFolder");

		String resultsFolder = destFolder + "results/";
		String cumulativeFolder = destFolder + "results/";
		String stateFolder = destFolder + "states/";
		String teamsFolder = destFolder + "best_teams/";

		runArguments.put("resultsFolder" , resultsFolder);
		runArguments.put("cumulativeFolder" , cumulativeFolder);
		runArguments.put("stateFolder" , stateFolder);
		runArguments.put("teamsFolder" , teamsFolder);


	}

}
