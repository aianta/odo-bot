package ca.ualberta.odobot.tpg.util.serializers;


import com.esotericsoftware.kryo.kryo5.Kryo;
import com.esotericsoftware.kryo.kryo5.Serializer;
import com.esotericsoftware.kryo.kryo5.io.Input;
import com.esotericsoftware.kryo.kryo5.io.Output;

import  ca.ualberta.odobot.tpg.MemoryModel;




public class MemoryModelSerializer extends Serializer<MemoryModel>{

	@Override
	public void write(Kryo kryo, Output output, MemoryModel model) {
		
		int rows = model.getRows();
		int columns = model.getColumns();
		double[][] memory = model.getMemory();
		double[] probabilities = model.getProbabilities();
		
		//int serializer
		output.writeInt(rows, false);
		output.writeInt(columns, false);
		
		//2d double array serializer:
		//just write each row I guess.
		for (int i = 0; i < rows; i++)
		{
			output.writeVarInt(memory[i].length + 1, true);
			output.writeDoubles(memory[i], 0, memory[i].length);
		}
		
		
		//double array serializer: 
		output.writeVarInt(probabilities.length + 1, true);
		output.writeDoubles(probabilities, 0, probabilities.length);
		
		
		
		//output.writeLong(instruction.getLongValue());
	}

	@Override
	public MemoryModel read(Kryo kryo, Input input, Class<? extends MemoryModel> model) {
		
		//read the two ints
		int rows = input.readInt(false);
		int columns = input.readInt(false);
		
		//read the 2d double array
		double[][] memory = new double[rows][columns];
		
		for (int i = 0; i < rows; i++)
		{
			int memlength = input.readVarInt(true);
			memory[i] = input.readDoubles(memlength - 1);
		}
		
		//read the 2d array
		
		int problength = input.readVarInt(true);
		double[] probabilities = input.readDoubles(problength - 1);
		
		
		MemoryModel result = new MemoryModel(rows, columns, memory, probabilities);
		
		return result;
	}
	
}
