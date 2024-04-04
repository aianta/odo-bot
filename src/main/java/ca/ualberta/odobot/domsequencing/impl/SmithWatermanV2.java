package ca.ualberta.odobot.domsequencing.impl;

import ca.ualberta.odobot.domsequencing.DOMSegment;
import ca.ualberta.odobot.domsequencing.DOMSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SmithWaterman implementation, porting over from:
 * https://github.com/slavianap/Smith-Waterman-Algorithm/blob/master/Script.py
 *
 *
 */
public class SmithWatermanV2 {

    private static Logger log = LoggerFactory.getLogger(SmithWatermanV2.class);

    DOMSequence sequence1;

    DOMSequence sequence2;

    enum Trace{
        STOP, LEFT, UP, DIAGONAL
    }

    Trace [][] tracingMatrix;

    int [][] h;

    List<String> alphabet;

    int gapPenalty;


    public SmithWatermanV2(int gapPenalty){
        this.gapPenalty = gapPenalty;
    }


    public void align(DOMSequence sequence1, DOMSequence sequence2){
        h = constructScoringMatrix(sequence1, sequence2);


        List<MatrixLocation> maxScores = getMaxScore(h);



        log.info("Found {} max scores", maxScores.size());
        maxScores.forEach(location->{
            log.info("MaxScore of {} found at [{},{}]", location.value, location.i, location.j);

            Alignment alignment = new Alignment();

            alignment = traceback(location, alignment, sequence1, sequence2);


            alignment.printAlignment();

        });





    }

    public Alignment traceback(MatrixLocation origin, Alignment alignment, DOMSequence sequence1, DOMSequence sequence2){

        int maxI = origin.i;
        int maxJ = origin.j;

        while (tracingMatrix[maxI][maxJ] != Trace.STOP){

            if(tracingMatrix[maxI][maxJ] == Trace.DIAGONAL){
                alignment.a.add(0, sequence1.get(maxI-1));
                alignment.b.add(0, sequence2.get(maxJ-1));
                maxI = maxI - 1;
                maxJ = maxJ - 1;

            } else if (tracingMatrix[maxI][maxJ] == Trace.UP) {
                alignment.a.add(0, sequence1.get(maxI-1));
                alignment.b.add(0, null);
                maxI = maxI - 1;

            } else if (tracingMatrix[maxI][maxJ] == Trace.LEFT) {
                alignment.a.add(0, null);
                alignment.b.add(0, sequence2.get(maxJ-1));
                maxJ = maxJ - 1;
            }


        }


        return alignment;
    }

    public class Alignment{
        public DOMSequence a = new DOMSequence();
        public DOMSequence b = new DOMSequence();

        public void printAlignment(){

            for (int i = 0; i < a.size(); i++){
                DOMSegment aValue = a.get(i);
                DOMSegment bValue = b.get(i);
                log.info("{}\t---\t{}", aValue == null? "null": aValue.tag() + "|" + aValue.className(), bValue == null?"null":bValue.tag() + "|" + bValue.className());

            }

        }
    }

    public int[][] constructScoringMatrix(DOMSequence sequence1, DOMSequence sequence2){

        int [][] _h = new int[sequence1.size() + 1][ sequence2.size() + 1];
        Trace [][] _tracingMatrix = new Trace[sequence1.size() + 1][ sequence2.size() + 1];

        for(int i = 0; i < _tracingMatrix.length; i++){
            for(int j = 0; j < _tracingMatrix[0].length; j++){
                _tracingMatrix[i][j] = Trace.STOP;
            }
        }

        log.info("_h initialized");

        int gapLength = 1;
        for(int i = 1; i < sequence1.size(); i++){
            for(int j = 1; j < sequence2.size(); j++){


                int _diag = _h[i-1][j-1]; //Value from diagonally up-left
                int _left = _h[i][j-1];   //Value from left
                int _top = _h[i-1][j];    //Value from above/top/up.

                int diagonalScore = _diag + score(sequence1.get(i-1), sequence2.get(j-1));
                int horizontalScore = _left + gapPenalty;
                int verticalScore = _top + gapPenalty;


                _h[i][j] = Collections.max(List.of(diagonalScore, horizontalScore, verticalScore, 0));

                if (_h[i][j] == 0){
                    _tracingMatrix[i][j] = Trace.STOP;
                }

                if(_h[i][j] == horizontalScore){
                    _tracingMatrix[i][j] = Trace.LEFT;
                }

                if(_h[i][j] == verticalScore){
                    _tracingMatrix[i][j] = Trace.UP;
                }

                if(_h[i][j] == diagonalScore){
                    _tracingMatrix[i][j] = Trace.DIAGONAL;
                }



            }
        }

        this.tracingMatrix = _tracingMatrix;

        return _h;


    }

    private int score(DOMSegment a, DOMSegment b){

        if(a.tag().equals(b.tag()) && a.className().equals(b.className())){
            return 1; //Match
        }else{
            return -1; //Mismatch
        }

    }

    private int penalty(int length){
        return length*gapPenalty;
    }

    private int[][] toSimpleMatrix(MatrixValue[][] input){
        int[][] result = new int[input.length][input[0].length];

        for(int i = 0; i < result.length; i++){
            for(int j = 0; j < result[0].length; j++){
                result[i][j] = input[i][j].value;
            }
        }

        return result;
    }

    private class MatrixValue{
        public int value = 0;

        int sourceI; // The i-index from which this value was computed.

        int sourceJ; // The j-index from which this value was computed.
    }

    private class MatrixLocation{
        public int i;
        public int j;

        public int value;
    }

    private List<MatrixLocation> getMaxScore(int [][] input){

        List<MatrixLocation> maxValues = new ArrayList<>();

        int maxValue = -1;
        for(int i = 0; i < input.length; i++){
            for(int j = 0; j < input[0].length; j++) {
                maxValue = Math.max(maxValue, input[i][j]);
            }
        }


        for(int i = 0; i < input.length; i++){
            for(int j = 0; j < input[0].length; j++){

                if(input[i][j] == maxValue){
                    MatrixLocation _location = new MatrixLocation();
                    _location.i = i;
                    _location.j = j;
                    _location.value = input[i][j];
                    maxValues.add(_location);
                }

            }
        }


        return maxValues;
    }


}
