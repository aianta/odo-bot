package ca.ualberta.odobot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OdoX reaches a task at the host of the request's guidanceServiceHost, on the task's own port.
 */
class TaskVerticleGuidanceHostTest {

    @Test
    void theConfiguredPortIsReplacedWithTheTasks(){
        assertEquals("172.23.0.4:41234", TaskVerticle.guidanceHost("172.23.0.4:7080", 41234));
        assertEquals("odobot:41234", TaskVerticle.guidanceHost("odobot:7080", 41234));
    }

    @Test
    void aHostWithoutAPortGetsTheTasks(){
        assertEquals("odobot:41234", TaskVerticle.guidanceHost("odobot", 41234));
    }

    @Test
    void aSchemeAndPathAreDropped(){
        assertEquals("odobot:41234", TaskVerticle.guidanceHost("wss://odobot:7080/", 41234));
    }

    @Test
    void anIpv6AddressKeepsItsColons(){
        assertEquals("[::1]:41234", TaskVerticle.guidanceHost("[::1]:7080", 41234));
        assertEquals("[::1]:41234", TaskVerticle.guidanceHost("[::1]", 41234));
    }

    @Test
    void aMissingHostIsAnError(){
        assertThrows(IllegalArgumentException.class, ()->TaskVerticle.guidanceHost(null, 41234));
        assertThrows(IllegalArgumentException.class, ()->TaskVerticle.guidanceHost(" ", 41234));
    }
}
