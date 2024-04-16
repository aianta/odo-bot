package ca.ualberta.odobot.semanticflow.navmodel;

import java.util.HashSet;
import java.util.Set;

/**
 * A collapse contains a set of odo-bot ids (UUIDs) which have been marked for collapse into a single node.
 */
public class Collapse {

    Set<String> ids = new HashSet<>();

}
