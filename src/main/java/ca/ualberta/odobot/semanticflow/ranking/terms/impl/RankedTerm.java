package ca.ualberta.odobot.semanticflow.ranking.terms.impl;

import edu.stanford.nlp.ling.CoreLabel;

public record RankedTerm(String term, double ranking) {
}
