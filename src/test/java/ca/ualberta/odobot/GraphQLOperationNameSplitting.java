package ca.ualberta.odobot;

import ca.ualberta.odobot.common.Utils;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GraphQLOperationNameSplitting {

    private static final Logger log = LoggerFactory.getLogger(GraphQLOperationNameSplitting.class);

    @Test
    public void testGraphQLOperationNameSplitting(){
        String operationName = "GetUserProfileById";

        String output = Utils.splitCamelCase(operationName);

        log.info("{}",output);


    }
}
