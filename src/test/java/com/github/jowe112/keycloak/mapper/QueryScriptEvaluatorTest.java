package com.github.jowe112.keycloak.mapper;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class QueryScriptEvaluatorTest {
    @Test
    public void testActualMultilineInput() {
        String script = "\"?query=\" + encodeURIComponent(`query { ldapUser\n(uid: \"${username}\")\n { givenName } \n}\n`)";
        String result = QueryScriptEvaluator.evaluate(script, Map.of("username", "testuser"));
        assertEquals("?query=query%20%7B%20ldapUser%0A(uid%3A%20%22testuser%22)%0A%20%7B%20givenName%20%7D%20%0A%7D%0A", result);
    }
}
