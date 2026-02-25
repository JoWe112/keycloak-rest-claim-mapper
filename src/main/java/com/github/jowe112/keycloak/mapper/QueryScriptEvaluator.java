package com.github.jowe112.keycloak.mapper;

import org.jboss.logging.Logger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Evaluates JavaScript {@code query.script} expressions using the GraalVM
 * Polyglot API.
 * <p>
 * Each invocation creates a sandboxed {@link Context} that:
 * <ul>
 * <li>Runs in the {@code js} language.</li>
 * <li>Has no access to native file I/O, network, or environment.</li>
 * <li>Is closed after evaluation to free resources.</li>
 * </ul>
 * Declared {@code query.param.K} values are bound as JavaScript top-level
 * variables so
 * scripts can reference them by name (e.g. {@code "?user=" + username}).
 */
public final class QueryScriptEvaluator {

    private static final Logger LOG = Logger.getLogger(QueryScriptEvaluator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QueryScriptEvaluator() {
    }

    /**
     * Evaluates the given JavaScript expression with the supplied variable
     * bindings.
     *
     * @param script    the JS expression, e.g. {@code "?user=" + username}
     * @param variables map of variable name → string value to inject as JS bindings
     * @return the string result of the expression, or {@code ""} on error
     */
    public static @NotNull String evaluate(@Nullable String script, @NotNull Map<String, String> variables) {
        if (script == null || script.isBlank()) {
            return "";
        }

        // Build a JS snippet that declares each variable, then evaluates the script
        // expression.
        // This avoids needing to use the Polyglot bindings API, which requires
        // allowAllAccess.
        StringBuilder fullScript = new StringBuilder();
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            try {
                // writeValueAsString adds the surrounding quotes and safely escapes
                // all control characters, newlines, and quotes as a valid JSON (and JS) string.
                String escapedValue = MAPPER.writeValueAsString(entry.getValue());
                fullScript.append("var ").append(entry.getKey())
                        .append(" = ").append(escapedValue).append(";\n");
            } catch (JsonProcessingException e) {
                LOG.errorf(e, "Failed to serialize JS parameter %s", entry.getKey());
            }
        }
        fullScript.append(script);

        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(false)
                .option("engine.WarnInterpreterOnly", "false")
                .build()) {

            Value result = ctx.eval("js", fullScript.toString());
            return result.asString();

        } catch (PolyglotException e) {
            LOG.errorf("QueryScript evaluation failed: %s | script: %s", e.getMessage(), script);
            return "";
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error evaluating query script: %s", script);
            return "";
        }
    }
}
