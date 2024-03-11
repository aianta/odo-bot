package ca.ualberta.odobot.tpg.util;

import com.esotericsoftware.kryo.kryo5.Kryo;
import com.esotericsoftware.kryo.kryo5.io.Input;
import com.esotericsoftware.kryo.kryo5.io.Output;
import ca.ualberta.odobot.tpg.actions.Action;
import ca.ualberta.odobot.tpg.actions.ActionLabel;
import ca.ualberta.odobot.tpg.actions.ActionProgram;
import ca.ualberta.odobot.tpg.actions.ActionType;
import ca.ualberta.odobot.tpg.Instruction;
import ca.ualberta.odobot.tpg.MemoryModel;
import ca.ualberta.odobot.tpg.Program;
import ca.ualberta.odobot.tpg.TPGAlgorithm;
import ca.ualberta.odobot.tpg.learners.Learner;
import ca.ualberta.odobot.tpg.teams.Team;
import ca.ualberta.odobot.tpg.TPGLearn;
import ca.ualberta.odobot.tpg.util.serializers.InstructionSerializer;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public class SaveLoad {

	Kryo kryo = new Kryo();

	public SaveLoad()
	{
		//allows Kryo to serialize references, which prevents issues when serializing cyclic structures.
		kryo.setReferences(true);

		//register all the stuff. long and arduous. You should only do this once, when you make
		//a SaveLoad object. 

		//kryo.register(sbbj_tpg_mem.TPGAlgorithm.class, new TPGAlgorithmSerializer());
		kryo.register(TPGAlgorithm.class);
		kryo.register(MemoryModel.class);
		//kryo.register(sbbj_tpg_mem.MemoryModel.class, new MemoryModelSerializer());
		kryo.register(Miscellaneous.class);
		kryo.register(OpenBoolean.class);
		kryo.register(OpenDouble.class);
		kryo.register(Pair.class);
		kryo.register(TPGLearn.class);
		kryo.register(Team.class);
		kryo.register(Learner.class);
		kryo.register(Action.class);
		kryo.register(Program.class);
		kryo.register(ActionType.class);
		kryo.register(ActionProgram.class);
		kryo.register(ActionLabel.class);
		kryo.register(Instruction.class, new InstructionSerializer());


		kryo.register(java.util.HashMap.class);
		kryo.register(java.util.ArrayList.class);
		kryo.register(String[].class);
		kryo.register(java.util.Locale.class);
		kryo.register(String[][].class);
		kryo.register(java.text.DecimalFormat.class);
		kryo.register(java.math.RoundingMode.class);
		kryo.register(java.text.DecimalFormatSymbols.class);
		kryo.register(java.util.HashSet.class);
		kryo.register(double[].class);
		kryo.register(java.util.LinkedList.class);
	}

	public void saveState(TPGAlgorithm tpgAlgorithm, String filepath) throws FileNotFoundException
	{
		File f = new File(filepath);
		f.getParentFile().mkdirs();

		//sets the location where the file will be saved.
		System.out.println("\nsaveState called with filepath " + filepath);
		Output output = new Output(new FileOutputStream(filepath));
		System.out.println("Successfully created the FileOutputStream.");

		//writes the TPGAlgorithm object itself
		kryo.writeObject(output, tpgAlgorithm);

		//Kryo is a little shit that doesn't like static variables, 
		//so we have to write them separately.

		//write tpgAlgorithm range and memory model
		kryo.writeObject(output, tpgAlgorithm.RNG);
		kryo.writeObject(output, tpgAlgorithm.memory);

		//write Team count
		kryo.writeObject(output, tpgAlgorithm.getRootTeamCount());

		//write Learner count
		kryo.writeObject(output, tpgAlgorithm.getLearnerCount());

		//write learner number of registers
		kryo.writeObject(output, tpgAlgorithm.getRegisterCount());


		//close the output.
		output.close();
	}

	public String saveTeam(Team t, long epochs, int rank, String destinationFolder) throws IOException {
		//sets the location where the file will be saved.
		System.out.println("\nsaveTeam called with filepath " + destinationFolder);

		Path dstPath = Path.of(destinationFolder);
		if(!Files.exists(dstPath)){
			Files.createDirectories(dstPath);
		}
		String folderWithFile = destinationFolder + "gen_" + epochs + "_rank_" + rank + "_T_" + t.getID() + ".bin";
		
		Output output = new Output(new FileOutputStream(folderWithFile));
		System.out.println("Successfully created the FileOutputStream.");

		//writes the team object itself
		kryo.writeObject(output, t);

		//Kryo is a little shit that doesn't like static variables, 
		//so we have to write them separately.
		//write Team count
		kryo.writeObject(output, t.count);

		//write Learner count
		kryo.writeObject(output, t.getLearners().get(0).count);
		
		//write learner number of registers
		kryo.writeObject(output, t.getLearners().get(0).REGISTERS);

		//close the output.
		output.close();

		return folderWithFile;
	}

	public TPGAlgorithm resumeLearning(String path) throws FileNotFoundException
	{

		Input kryoinput = null;
		try {
			kryoinput = new Input(new FileInputStream(path));
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		TPGAlgorithm tpgAlgorithm = kryo.readObject(kryoinput, TPGAlgorithm.class);

		//read tpgAlgorithm range and memory model
		tpgAlgorithm.RNG= kryo.readObject(kryoinput, Random.class);
		tpgAlgorithm.memory= kryo.readObject(kryoinput, MemoryModel.class);

		//read Team count
		Long teamIDCount = kryo.readObject(kryoinput, Long.class);

		//read Learner count
		Long learnerIDCount = kryo.readObject(kryoinput, Long.class);

		//read learner number of registers
		int learnerNumRegisters = kryo.readObject(kryoinput, Integer.class);


		kryoinput.close();


		//tpg.tpglearn.rampant.TPGLearn tpg = tpgAlgorithm.getTPGLearn();
		TPGLearn tpg = tpgAlgorithm.getTPGLearn();


		tpg.getRootTeams().get(0).count = teamIDCount;
		tpg.getRootTeams().get(0).getLearners().get(0).count = learnerIDCount;
		tpg.getRootTeams().get(0).getLearners().get(0).REGISTERS = learnerNumRegisters;

		//System.out.println("the TPGAlgorithm seems to have loaded fine!");
		//System.out.println("\nList of teams after loading: \n");


		//tpgAlgorithm.getMemory().printMemory();
		tpgAlgorithm = TPGAlgorithm.getInstance(tpgAlgorithm);

		return tpgAlgorithm;

	}
	
	public Team loadTeam(String path) throws FileNotFoundException
	{

		Input kryoinput = null;
		
		try {
			kryoinput = new Input(new FileInputStream(path));
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		Team t = kryo.readObject(kryoinput, Team.class);

		//read Team count
		Long teamIDCount = kryo.readObject(kryoinput, Long.class);

		//read Learner count
		Long learnerIDCount = kryo.readObject(kryoinput, Long.class);

		//read learner number of registers
		int learnerNumRegisters = kryo.readObject(kryoinput, Integer.class);


		kryoinput.close();

		t.count = teamIDCount;
		t.getLearners().get(0).count = learnerIDCount;
		t.getLearners().get(0).REGISTERS = learnerNumRegisters;

		System.out.println("The team seems to have loaded fine!");
	
		return t;

	}

	

}
