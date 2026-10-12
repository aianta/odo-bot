package ca.ualberta.odobot.task;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A task's environment comes from the task first, then the experiment request, then defaults.
 */
class TaskEnvironmentTest {

    private static JsonObject experiment(){
        return new JsonObject()
                .put("webAppURL", "http://172.23.0.2/admin")
                .put("firefoxDockerGridURL", "http://172.23.0.3:4444")
                .put("logUIHost", "172.26.128.1:8000")
                .put("logUIUsername", "user")
                .put("logUIPassword", "secret");
    }

    @Test
    void anOldRequestWithOnlyExperimentFieldsKeepsWorking(){
        TaskEnvironment environment = TaskEnvironment.resolve(new JsonObject().put("id", "t"), experiment());

        assertEquals("http://172.23.0.3:4444", environment.gridUrl());
        assertEquals("http://172.23.0.2/admin", environment.startUrl());
        assertEquals(List.of("http://172.23.0.2"), environment.targetHosts(), "defaults to the start page's origin");
        assertEquals("172.26.128.1:8000", environment.logUiHost());
        assertEquals(Map.of(), environment.sites());
    }

    @Test
    void theTasksEnvironmentOverridesTheExperiments(){
        JsonObject task = new JsonObject().put("environment", new JsonObject()
                .put("firefoxDockerGridURL", "http://firefox-3:4444")
                .put("startUrl", "http://shopping-3:7780/admin")
                .put("targetHosts", new JsonArray().add("http://shopping-3:7780"))
                .put("sites", new JsonObject().put("shopping_admin", "http://shopping-3:7780")));

        TaskEnvironment environment = TaskEnvironment.resolve(task, experiment().put("targetHosts", new JsonArray().add("*")));

        assertEquals("http://firefox-3:4444", environment.gridUrl());
        assertEquals("http://shopping-3:7780/admin", environment.startUrl());
        assertEquals(List.of("http://shopping-3:7780"), environment.targetHosts());
        assertEquals(Map.of("shopping_admin", "http://shopping-3:7780"), environment.sites());
        assertEquals("user", environment.logUiUsername(), "fields the task leaves out come from the experiment");
    }

    @Test
    void experimentTargetHostsApplyWhenTheTaskHasNone(){
        TaskEnvironment environment = TaskEnvironment.resolve(new JsonObject(), experiment().put("targetHosts", new JsonArray().add("*")));

        assertEquals(List.of("*"), environment.targetHosts());
    }

    @Test
    void aTopLevelStartUrlStillWorks(){
        TaskEnvironment environment = TaskEnvironment.resolve(new JsonObject().put("startUrl", "http://172.23.0.6:8023/explore"), experiment());

        assertEquals("http://172.23.0.6:8023/explore", environment.startUrl());
        assertEquals(List.of("http://172.23.0.6:8023"), environment.targetHosts());
    }

    @Test
    void theTasksUserLocationIsNeverTheStartPage(){
        JsonObject task = new JsonObject().put("userLocation", "http://localhost:8088/login/canvas");

        assertEquals("http://172.23.0.2/admin", TaskEnvironment.resolve(task, experiment()).startUrl());
    }

    @Test
    void aTaskWithoutAStartPageIsRejected(){
        JsonObject request = experiment();
        request.remove("webAppURL");

        assertThrows(IllegalArgumentException.class, ()->TaskEnvironment.resolve(new JsonObject().put("userLocation", "http://x"), request));
    }

    @Test
    void theLogUiSettingsAreRequiredAtOneLevelOrTheOther(){
        JsonObject request = experiment();
        request.remove("logUIUsername");

        assertThrows(IllegalArgumentException.class, ()->TaskEnvironment.resolve(new JsonObject(), request));
        TaskEnvironment fromTask = TaskEnvironment.resolve(
                new JsonObject().put("environment", new JsonObject().put("logUIUsername", "task-user")), request);
        assertEquals("task-user", fromTask.logUiUsername());
    }

    @Test
    void anExperimentWithoutAWebAppUrlCanNameEachTasksStartPage(){
        JsonObject request = experiment();
        request.remove("webAppURL");
        request.remove("firefoxDockerGridURL");

        TaskEnvironment environment = TaskEnvironment.resolve(new JsonObject().put("environment", new JsonObject()
                .put("startUrl", "http://172.23.0.5")
                .put("firefoxDockerGridURL", "http://172.23.0.9:4444")), request);

        assertEquals("http://172.23.0.5", environment.startUrl());
        assertEquals("http://172.23.0.9:4444", environment.gridUrl());
    }

    @Test
    void fieldsOfTheWrongTypeAreRejected(){
        assertThrows(IllegalArgumentException.class, ()->TaskEnvironment.resolve(new JsonObject().put("environment", "nope"), experiment()));
        assertThrows(IllegalArgumentException.class, ()->TaskEnvironment.resolve(
                new JsonObject().put("environment", new JsonObject().put("targetHosts", "http://a")), experiment()));
        assertThrows(IllegalArgumentException.class, ()->TaskEnvironment.resolve(
                new JsonObject().put("environment", new JsonObject().put("startUrl", 5)), experiment()));
    }

    @Test
    void theSavedEnvironmentLeavesOutThePassword(){
        JsonObject saved = TaskEnvironment.resolve(new JsonObject(), experiment()).toJson();

        assertFalse(saved.encode().contains("secret"));
        assertEquals("http://172.23.0.2/admin", saved.getString("startUrl"));
    }

    @Test
    void anOriginWithoutAProtocolIsTakenToBeHttp(){
        assertEquals("http://172.23.0.2", TaskEnvironment.origin("172.23.0.2/admin"));
        assertEquals("https://example.com:8443", TaskEnvironment.origin("https://example.com:8443/a?b=c"));
    }
}
