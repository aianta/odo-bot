package ca.ualberta.odobot.tpg.util.serializers;

import com.esotericsoftware.kryo.kryo5.Kryo;
import com.esotericsoftware.kryo.kryo5.Serializer;
import com.esotericsoftware.kryo.kryo5.io.Input;
import com.esotericsoftware.kryo.kryo5.io.Output;

import ca.ualberta.odobot.tpg.Instruction;

import java.util.BitSet;

//@DefaultSerializer(InstructionSerializer.class)
public class InstructionSerializer extends Serializer<Instruction>{

	@Override
	public void write(Kryo kryo, Output output, Instruction instruction) {
		
		BitSet thing = (BitSet) instruction;
		
		long[] values = thing.toLongArray();
		output.writeVarInt(values.length, true);
		output.writeLongs(values, 0, values.length);
		
		//output.writeLong(instruction.getLongValue());
	}

	@Override
	public Instruction read(Kryo kryo, Input input, Class<? extends Instruction> instruction) {
		
		int length = input.readVarInt(true);
		long[] values = input.readLongs(length);
		BitSet set = BitSet.valueOf(values);
		
		Instruction result = new Instruction(set);
		
		return result;
	}

	
}
