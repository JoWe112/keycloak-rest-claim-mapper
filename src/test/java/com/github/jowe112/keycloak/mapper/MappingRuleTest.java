package com.github.jowe112.keycloak.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MappingRuleTest {

    @Test
    void plainClaimName() {
        MappingRule rule = new MappingRule("role", "user_role");
        assertEquals("user_role", rule.getClaimName());
        assertFalse(rule.isPreserveJsonStructure());
        assertEquals("role→user_role", rule.toString());
    }

    @Test
    void jsonPrefixStripped() {
        MappingRule rule = new MappingRule("$.data.users", "json:users");
        assertEquals("users", rule.getClaimName());
        assertTrue(rule.isPreserveJsonStructure());
        assertTrue(rule.isJsonPath());
    }

    @Test
    void toStringIncludesJsonPrefix() {
        MappingRule rule = new MappingRule("$.items", "json:items");
        assertEquals("$.items→json:items", rule.toString());
    }

    @Test
    void jsonPrefixOnSimpleField() {
        MappingRule rule = new MappingRule("data", "json:payload");
        assertEquals("payload", rule.getClaimName());
        assertTrue(rule.isPreserveJsonStructure());
        assertFalse(rule.isJsonPath());
    }
}
