package com.github.jowe112.keycloak.mapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class QueryScriptEvaluatorTest {
    @Test
    public void testActualMultilineInput() {
        String script = "\"?query=\" + encodeURIComponent(`query { ldapUser\n(uid: \"${username}\")\n { givenName } \n}\n`)";
        String result = QueryScriptEvaluator.evaluate(script, Map.of("username", "testuser"));
        assertEquals("?query=query%20%7B%20ldapUser%0A(uid%3A%20%22testuser%22)%0A%20%7B%20givenName%20%7D%20%0A%7D%0A", result);
    }

    /**
     * An infinite-loop script must be cancelled by the statement limit and return
     * "" rather than hanging. The test timeout is a safety net — if the resource
     * limit did not fire, the loop would run forever and the test would fail here.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testInfiniteLoopIsCancelled() {
        String script = "var i = 0; while (true) { i++; } i";
        String result = QueryScriptEvaluator.evaluate(script, Map.of());
        assertEquals("", result);
    }
}
