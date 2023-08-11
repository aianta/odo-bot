package ca.ualberta.odobot.semanticflow.model;

/**
 * Fixed points are artifacts which are uniquely identifiable across all timelines. They can
 * be used as anchors to align timelines in process models.
 *
 * In practice, this means that FixedPoint artifacts can simply return their own unique activity
 * label without having to go through embedding/clustering.
 */
public interface FixedPoint {

    String getActivityLabel();

}
