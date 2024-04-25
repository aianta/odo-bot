package ca.ualberta.odobot.semanticflow.navmodel;


import java.util.List;


public record Collapse (
        CollapsingEvaluatorV2.MatrixElement startingAnchor,
        CollapsingEvaluatorV2.MatrixElement endingAnchor,
        List<List<CollapsingEvaluatorV2.MatrixElement>> instances
) {}
