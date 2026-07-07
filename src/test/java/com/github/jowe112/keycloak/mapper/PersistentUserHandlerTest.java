package com.github.jowe112.keycloak.mapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the structured-value cache codec in {@link PersistentUserHandler}.
 * <p>
 * These cover the collision-proof marker (issue #55): a scalar value that
 * happens to start with {@code "json:"} must not be mistaken for a serialized
 * structure.
 */
class PersistentUserHandlerTest {

    // ── isStructuredValue ─────────────────────────────────────────────────────

    @Test
    void mapIsStructured() {
        assertTrue(PersistentUserHandler.isStructuredValue(Map.of("a", 1)));
    }

    @Test
    void listOfMapsIsStructured() {
        assertTrue(PersistentUserHandler.isStructuredValue(List.of(Map.of("a", 1))));
    }

    @Test
    void scalarIsNotStructured() {
        assertFalse(PersistentUserHandler.isStructuredValue("admin"));
    }

    @Test
    void listOfScalarsIsNotStructured() {
        assertFalse(PersistentUserHandler.isStructuredValue(List.of("a", "b")));
    }

    @Test
    void emptyListIsNotStructured() {
        assertFalse(PersistentUserHandler.isStructuredValue(List.of()));
    }

    // ── encode → decode round-trips ──────────────────────────────────────────

    @Test
    void encodedStructuredValueCarriesTheMarker() throws Exception {
        String encoded = PersistentUserHandler.encodeStructured(List.of(Map.of("cn", "John")));
        assertTrue(encoded.startsWith(PersistentUserHandler.STRUCTURED_PREFIX),
                "encoded value must start with the structured marker");
    }

    @Test
    void structuredListRoundTrips() throws Exception {
        Object original = List.of(
                Map.of("cn", "John", "memberOf", List.of("g1", "g2")),
                Map.of("cn", "Jane"));
        String encoded = PersistentUserHandler.encodeStructured(original);
        Object decoded = PersistentUserHandler.decodeCached(List.of(encoded));
        assertEquals(original, decoded);
    }

    @Test
    void structuredMapRoundTrips() throws Exception {
        Object original = Map.of("id", "team007", "tags", List.of("a", "b"));
        String encoded = PersistentUserHandler.encodeStructured(original);
        Object decoded = PersistentUserHandler.decodeCached(List.of(encoded));
        assertEquals(original, decoded);
    }

    // ── collision safety (the point of issue #55) ────────────────────────────

    @Test
    void scalarBeginningWithJsonPrefixIsReturnedVerbatim() {
        // A legitimate scalar value that starts with the bare "json:" text must
        // NOT be treated as a serialized structure — only the U+E000 marker does.
        Object decoded = PersistentUserHandler.decodeCached(List.of("json:not-really-structured"));
        assertEquals("json:not-really-structured", decoded);
    }

    @Test
    void legacyBareJsonPrefixIsNoLongerDecodedAsStructured() {
        // Pre-fix caches used a bare "json:" prefix. With the new marker these are
        // read back as plain strings (until the TTL refreshes them), not parsed.
        String legacy = "json:{\"cn\":\"John\"}";
        Object decoded = PersistentUserHandler.decodeCached(List.of(legacy));
        assertEquals(legacy, decoded);
    }

    // ── plain scalar / list decoding ─────────────────────────────────────────

    @Test
    void singleScalarDecodesToString() {
        assertEquals("engineering", PersistentUserHandler.decodeCached(List.of("engineering")));
    }

    @Test
    void multipleValuesDecodeToList() {
        assertEquals(List.of("a", "b", "c"),
                PersistentUserHandler.decodeCached(List.of("a", "b", "c")));
    }

    @Test
    void emptyOrNullDecodesToNull() {
        assertNull(PersistentUserHandler.decodeCached(List.of()));
        assertNull(PersistentUserHandler.decodeCached(null));
    }
}
