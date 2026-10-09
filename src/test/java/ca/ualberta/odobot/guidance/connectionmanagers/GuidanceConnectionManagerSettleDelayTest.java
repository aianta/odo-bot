package ca.ualberta.odobot.guidance.connectionmanagers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuidanceConnectionManagerSettleDelayTest {

    @Test
    void waitsTheFullDelayEarlyOn() {
        assertEquals(5000, GuidanceConnectionManager.settleDelay(0, 5000, 30000));
        assertEquals(5000, GuidanceConnectionManager.settleDelay(25000, 5000, 30000));
    }

    @Test
    void cutsTheDelaySoTheWaitEndsAtTheMax() {
        assertEquals(2000, GuidanceConnectionManager.settleDelay(28000, 5000, 30000));
    }

    @Test
    void sendsRightAwayOnceTheMaxIsReached() {
        assertEquals(1, GuidanceConnectionManager.settleDelay(30000, 5000, 30000));
        assertEquals(1, GuidanceConnectionManager.settleDelay(45000, 5000, 30000));
    }
}
