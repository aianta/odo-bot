package ca.ualberta.odobot.tpg;



import java.util.ArrayList;

//since learners will have a bid program
//and an action program, it seems to make more sense
//for them to just have a program class i guess idk
public class Program {

    // An array representing the general purpose registers for this program.
    public double[] registers = null;

    // The program itself; an ArrayList of Instructions.
    public ArrayList<Instruction> program = new ArrayList<Instruction>();

    public int registerCount = 0;

    public int maxProgSize = 0;

    public Program(int registerCount, int maxProgSize, boolean canWrite)
    {
        this.registerCount = registerCount;
        this.maxProgSize = maxProgSize;

        //make a new randomized program
        createNewRandomizedProgram(maxProgSize, canWrite);

        //initialize the registers for this program.
        registers = new double[registerCount];
    }

    //use this for making a copy of another program.
    public Program(Program other)
    {
        for (int i = 0; i < other.size(); i++)
        {
            program.add(other.getInstruction(i));
        }

        this.registerCount = other.getRegisterCount();
        this.maxProgSize = other.getMaxProgSize();
        registers = new double[registerCount];
    }

    public Program()
    {

    }


    protected void createNewRandomizedProgram(int maxProgSize, boolean canWrite)
    {
        ArrayList<Instruction> program = new ArrayList<Instruction>();

        // Create a new Instruction variable and make sure it's initialized to null
        Instruction in = null;

        // Generate a program up to maxProgSize, with a minimum of 1 instruction.
        int progSize = 1 + ((int)(TPGAlgorithm.RNG.nextDouble() * maxProgSize));

        // Randomize progSize many instructions and store them as the current Learner's program
        for( int i=0; i < progSize; i++ )
        {
            // Create a new empty Instruction (binary string of all 0's.
            in = new Instruction();

            // TODO: This while loop condition is hacked to stop memory
            //       from ever being accessed (read or write). More robust
            //       code is needed.
            canWrite = false;
            do
            {
                // There's a 50% chance for each bit to be set to 1.
                // See the Instruction class for default lengths.
                for( int j=0; j < in.size(); j++ )
                    if( TPGAlgorithm.RNG.nextDouble() < 0.5 )
                        in.flip(j);
            } while((!canWrite
                    && (in.getOperationRegister().getLongValue() % Instruction.OPERATION_COUNT) == Instruction.WRIT_VALUE)
                    || (in.getModeRegister().getLongValue() % Instruction.MODE_COUNT) == Instruction.mode2_VALUE);

            // Add the randomly generated instruction to the Learner's program
            program.add(in);
        }

        //overwrite the current program.
        this.program = program;
    }

    // Run the program on the given input feature set and return a pre-bid output
    public double[] run( double[] inputFeatures )
    {
        int mode;
        int operation;

        int destinationRegister;
        double sourceValue;

        // For every instruction in this program:
        for( Instruction instruction : program )
        {
            // Retrieve the mode register
            mode = (int)(instruction.getModeRegister().getLongValue() % Instruction.MODE_COUNT);

            // Retrieve the operation register
            operation = (int)(instruction.getOperationRegister().getLongValue() % Instruction.OPERATION_COUNT);

            // Retrieve the destination register
            destinationRegister = (int) instruction.getDestinationRegister().getLongValue() % registerCount;

            // Mode0 lets an instruction decide between using the input feature set or the general purpose registers
            if(mode == Instruction.mode0_VALUE)
            {
                sourceValue = registers[ (int) instruction.getSourceRegister().getLongValue() % registerCount ];
            }
            else if(mode == Instruction.mode1_VALUE)
            {
                sourceValue = inputFeatures[ (int) (instruction.getSourceRegister().getLongValue() % inputFeatures.length) ];
            }
            else
            {
                sourceValue = TPGAlgorithm.memory.read( (int) (instruction.getSourceRegister().getLongValue()) );
            }

            // Perform the appropriate operation
            if( operation == Instruction.SUM_VALUE )
                registers[destinationRegister] += sourceValue;
            else if( operation == Instruction.DIFF_VALUE )
                registers[destinationRegister] -= sourceValue;
            else if( operation == Instruction.PROD_VALUE )
                registers[destinationRegister] *= 2;
            else if( operation == Instruction.DIV_VALUE )
                registers[destinationRegister] /= 2;
                //			else if( operation == Instruction.COS_VALUE )
                //				registers[destinationRegister] = Math.cos( sourceValue );
                //			else if( operation == Instruction.LOG_VALUE )
                //				registers[destinationRegister] = Math.log( Math.abs(sourceValue) );
                //			else if( operation == Instruction.EXP_VALUE )
                //				registers[destinationRegister] = Math.exp( sourceValue );
            else if( operation == Instruction.COND_VALUE )
            {
                if( registers[destinationRegister] < sourceValue )
                    registers[destinationRegister] *= -1;
            }
            else if( operation == Instruction.WRIT_VALUE )
            {
                TPGAlgorithm.memory.write(registers);
            }
            else
            {
                System.err.println("LEARNER: UNKNOWN_OP: " + operation);
                throw new RuntimeException("Invalid Operation found in Learner.run()");
            }

            // If the value of registers[destination] is infinite or not a number, zero it
            if( Double.isInfinite(registers[destinationRegister]) || Double.isNaN(registers[destinationRegister]) )
                registers[destinationRegister] = 0;
        }

        // Return the value of the first general purpose register
        return registers;
    }

    // Perform various mutation operations to this program
    public boolean mutateProgram( double programDelete, double programAdd, double programSwap, double programMutate, int maxProgramSize, boolean canWrite)
    {
        boolean changed = false;
        int i = 0;
        int j = 0;

        // Choose a random instruction from the program set and remove it.
        if( program.size() > 1 && TPGAlgorithm.RNG.nextDouble() < programDelete )
        {
            i = (int) (TPGAlgorithm.RNG.nextDouble() * program.size());
            program.remove(i);

            changed = true;
        }

        // Insert a random instruction into the program set.
        if( program.size() < maxProgramSize && TPGAlgorithm.RNG.nextDouble() < programAdd )
        {
            Instruction instruction = null;

            // TODO: This while loop condition is hacked to stop memory
            //       from ever being accessed (read or write). More robust
            //       code is needed.
            canWrite = false;
            do
            {
                instruction = Instruction.newRandom();
            } while((!canWrite
                    && (instruction.getOperationRegister().getLongValue() % Instruction.OPERATION_COUNT) == Instruction.WRIT_VALUE)
                    || (instruction.getModeRegister().getLongValue() % Instruction.MODE_COUNT) == Instruction.mode2_VALUE);

            i = (int) (TPGAlgorithm.RNG.nextDouble() * (program.size()+1));

            program.add( i, instruction );

            changed = true;
        }

        // Flip a single bit of a random instruction from the program set.
        if( TPGAlgorithm.RNG.nextDouble() < programMutate )
        {
            // TODO: This while loop condition is hacked to stop memory
            //       from ever being accessed (read or write). More robust
            //       code is needed.
            canWrite = false;
            do
            {
                i = (int) TPGAlgorithm.RNG.nextDouble() * program.size();
                j = (int) TPGAlgorithm.RNG.nextDouble() * Instruction.INSTRUCTION_SIZE;

                program.get(i).flip(j);
            } while((!canWrite
                    && (program.get(i).getOperationRegister().getLongValue() % Instruction.OPERATION_COUNT) == Instruction.WRIT_VALUE)
                    || (program.get(i).getModeRegister().getLongValue() % Instruction.MODE_COUNT) == Instruction.mode2_VALUE);

            changed = true;
        }

        // Swap the positions of two instructions in the bid se.
        if( program.size() > 1 && TPGAlgorithm.RNG.nextDouble() < programSwap )
        {
            i = (int) (TPGAlgorithm.RNG.nextDouble() * program.size());

            // Keep randomizing a second integer until it's not equal to the first
            do
            {
                j = (int) (TPGAlgorithm.RNG.nextDouble() * program.size());
            }
            while( i == j );

            // Swap the two instructions
            Instruction temp = program.get(i);
            program.set(i, program.get(j));
            program.set(j, temp);

            changed = true;
        }

        // If this Learner's program was mutated, return true
        return changed;
    }

    public int size()
    {
        return program.size();
    }

    public boolean equals( Object object )
    {
        if( !(object instanceof Program) )
            return false;
        if( object == this )
            return true;

        Program other = (Program)object;
        //if the two programs aren't of equal size then they're not the same program
        if (program.size() != other.program.size())
        {
            return false;
        }

        //else, compare the instructions of each one.
        for (int i = 0; i < program.size(); i++)
        {
            //if the instructions do not match then return false
            if (!(program.get(i).equals(other.program.get(i))))
            {
                return false;
            }
        }

        //else return true;
        return true;
    }

    public Instruction getInstruction(int index)
    {
        //return a copy of that program's instruction
        return new Instruction(program.get(index));
    }

    public double[] getRegisters() {
        return registers;
    }

    public int getRegisterCount() {
        return registerCount;
    }

    public int getMaxProgSize() {
        return maxProgSize;
    }


}
