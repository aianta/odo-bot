package ca.ualberta.odobot;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProbActionIsTeamParamEvolutionTest {
    private static final Logger log = LoggerFactory.getLogger(ProbActionIsTeamParamEvolutionTest.class);
    @Test
    public void test(){

        double actionIsTeam = 0.0;
        int generation = 0;
        while (generation < 11000){
            if(generation < 1000){
                actionIsTeam = 0.0;
            }else{
                actionIsTeam = (((double)generation/1000.0) % 1000)/100.0;
            }

            log.info("actionIsTeam: {} generation: {}", actionIsTeam, generation);
            generation++;
        }



    }
}
