package com.github.jowe112.keycloak.mapper;

import org.jboss.logging.Logger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
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

    static {
        // The Truffle native-attach library cannot survive fat-JAR shading, which
        // produces a noisy WARNING at startup. Functionality is unaffected — scripts
        // run in interpreter mode, which is perfectly adequate for simple query-string
        // expressions. Respect any explicit override set via -D on the JVM command line.
        System.setProperty("polyglotimpl.AttachLibraryFailureAction",
                System.getProperty("polyglotimpl.AttachLibraryFailureAction", "ignore"));
    }

    private static final Logger LOG = Logger.getLogger(QueryScriptEvaluator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Upper bound on the number of statements a query script may execute before
     * it is forcibly cancelled. A script that only builds a query string runs a
     * handful of statements, so this generous ceiling never affects legitimate
     * use, but it stops a runaway or malicious loop from tying up a worker thread
     * until the handler's 10-second fetch timeout fires. Supported in GraalVM
     * Community Edition (statement counting, not CPU-time sandboxing).
     */
    private static final long STATEMENT_LIMIT = 100_000L;

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

        ResourceLimits limits = ResourceLimits.newBuilder()
                .statementLimit(STATEMENT_LIMIT, null)
                .build();

        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(false)
                .option("engine.WarnInterpreterOnly", "false")
                .resourceLimits(limits)
                .build()) {

            Value result = ctx.eval("js", fullScript.toString());
            return result.asString();

        } catch (PolyglotException e) {
            if (e.isResourceExhausted()) {
                LOG.errorf("QueryScript exceeded the %d-statement limit and was cancelled | script: %s",
                        STATEMENT_LIMIT, script);
            } else {
                LOG.errorf("QueryScript evaluation failed: %s | script: %s", e.getMessage(), script);
            }
            return "";
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error evaluating query script: %s", script);
            return "";
        }
    }
}
