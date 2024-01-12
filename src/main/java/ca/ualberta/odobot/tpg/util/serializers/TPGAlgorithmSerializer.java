package ca.ualberta.odobot.tpg.util.serializers;

import com.esotericsoftware.kryo.kryo5.Kryo;
import com.esotericsoftware.kryo.kryo5.Serializer;
import com.esotericsoftware.kryo.kryo5.io.Input;
import com.esotericsoftware.kryo.kryo5.io.Output;

import ca.ualberta.odobot.tpg.MemoryModel;
import ca.ualberta.odobot.tpg.TPGAlgorithm;
import ca.ualberta.odobot.tpg.TPGLearn;

import java.util.HashMap;
import java.util.Random;



public class TPGAlgorithmSerializer extends Serializer<TPGAlgorithm>{
	@Override
	public void write(Kryo kryo, Output output, TPGAlgorithm tpgAlgorithm) {
		kryo.register(Random.class);
		kryo.register(java.util.concurrent.atomic.AtomicLong.class);
		
		
		//kryo.register(sbbj_tpg_mem.TPGLearn.class);
		//write arguments, a hashmap
		kryo.writeObject(output, tpgAlgorithm.getArguments());

		//write RNG
		kryo.writeObject(output, tpgAlgorithm.getRNG());

		//write memory
		MemoryModelSerializer memoryModelSerializer = new MemoryModelSerializer();
		kryo.writeObject(output, tpgAlgorithm.getMemory(), memoryModelSerializer);

		//write tpgLearn
		kryo.writeObject(output, tpgAlgorithm.getTPGLearn());

		//write tpgPlay



	}

	@Override
	public TPGAlgorithm read(Kryo kryo, Input input, Class<? extends TPGAlgorithm> tpgAlgorithm) {
		
		kryo.register(Random.class);
		kryo.register(java.util.concurrent.atomic.AtomicLong.class);
		
		MemoryModelSerializer memoryModelSerializer = new MemoryModelSerializer();
		HashMap<String, String> arguments = kryo.readObject(input, HashMap.class);
		Random RNG = kryo.readObject(input, Random.class);
		MemoryModel memory = kryo.readObject(input, MemoryModel.class, memoryModelSerializer);
		TPGLearn tpgLearn = kryo.readObject(input, TPGLearn.class);

		TPGAlgorithm result = new TPGAlgorithm(arguments, RNG, memory, tpgLearn);
		return result;
	}
}
