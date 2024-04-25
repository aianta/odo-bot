package ca.ualberta.odobot.semanticflow.navmodel;


import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


public record Collapse (
        CollapsingEvaluator.PathElement startingAnchor,
        CollapsingEvaluator.PathElement endingAnchor,
        List<List<CollapsingEvaluator.PathElement>> instances
) {

    /**
     * This is kind of just a matrix transpose of the instances.
     *
     * So if instances looked like this:
     *
     * StartAnchor -> A -> B -> C -> EndAnchor
     * StartAnchor -> D -> E -> F -> EndAnchor
     * StartAnchor -> H -> I -> J -> EndAnchor
     *
     * This method will return an iterator for the following list:
     *
     * [
     *  [A, D, H],
     *  [B, E, I],
     *  [C, F, J]
     * ]
     *
     * The expectation is that A, D, H are mergable into a single node, then B, E, I are meragable into a single node, etc.
     *
     * @return
     */
    public Iterator<List<CollapsingEvaluator.PathElement>> mergableIterator(){

        List<List<CollapsingEvaluator.PathElement>> result = new ArrayList<>();

        List<Iterator<CollapsingEvaluator.PathElement>> iterators = new ArrayList<>();

        //Initialize the list of iterators with iterators for each instance starting at index 1, that is after the starting anchor.
        instances.forEach(instances->iterators.add(instances.listIterator(1)));


        int index = 1; //Avoid returning the starting anchor.
        int lastIndex = instances.get(0).size()-1; //Avoid returning the ending anchor.

        while (index < lastIndex){

            List<CollapsingEvaluator.PathElement> elementsAtIndex = new ArrayList<>();

            iterators.forEach(iterator->{

                if(iterator.hasNext()){
                    elementsAtIndex.add(iterator.next());
                }

            });

            index++;
            result.add(elementsAtIndex);
        }

        return result.iterator();
    }

}
