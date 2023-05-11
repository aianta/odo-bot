package ca.ualberta.odobot.logpreprocessor.executions;

/**
 * Describes where an external artifact is stored.
 */
public record ExternalArtifact(Location location, String path) {

    public enum Location{
        LOCAL_FILE_SYSTEM
    }

}
