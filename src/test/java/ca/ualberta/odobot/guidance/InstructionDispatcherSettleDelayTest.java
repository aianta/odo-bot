package ca.ualberta.odobot.guidance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InstructionDispatcherSettleDelayTest {

    @Test
    void waitsTheFullDelayEarlyOn() {
        assertEquals(5000, InstructionDispatcher.settleDelay(0, 5000, 30000));
        assertEquals(5000, InstructionDispatcher.settleDelay(25000, 5000, 30000));
    }

    @Test
    void cutsTheDelaySoTheWaitEndsAtTheMax() {
        assertEquals(2000, InstructionDispatcher.settleDelay(28000, 5000, 30000));
    }

    @Test
    void sendsRightAwayOnceTheMaxIsReached() {
        assertEquals(1, InstructionDispatcher.settleDelay(30000, 5000, 30000));
        assertEquals(1, InstructionDispatcher.settleDelay(45000, 5000, 30000));
    }

    @Test
    void bothModesKeepTheSettleTiming() {
        for(ExecutionMode mode: ExecutionMode.values()){
            DispatchPolicy policy = DispatchPolicy.forMode(mode);
            assertTrue(policy.settleGate());
            assertEquals(5000, policy.settleDelay());
            assertEquals(30000, policy.settleMaxDelay());
        }
    }
}
