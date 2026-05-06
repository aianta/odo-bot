package ca.ualberta.odobot;

import ca.ualberta.odobot.common.Utils;
import org.junit.jupiter.api.Test;

public class GraphQLOperationNameSplitting {

    @Test
    public void testGraphQLOperationNameSplitting(){
        String operationName = "GetUserProfileById";

        String output = Utils.splitCamelCase(operationName);
    }
}
