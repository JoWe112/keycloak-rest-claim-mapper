package com.github.jowe112.keycloak.mapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonPathMapperTest {

    // ── Backward-compatible (no json: prefix) ────────────────────────────────

    @Test
    void simpleFieldScalar() {
        String json = """
                {"role": "admin", "department": "engineering"}
                """;
        List<MappingRule> rules = List.of(new MappingRule("role", "user_role"));
        Map<String, Object> claims = JsonPathMapper.map(json, rules);

        assertEquals("admin", claims.get("user_role"));
    }

    @Test
    void simpleFieldArrayOfStringsCollapseSingle() {
        String json = """
                {"tags": ["only-one"]}
                """;
        List<MappingRule> rules = List.of(new MappingRule("tags", "user_tags"));
        Map<String, Object> claims = JsonPathMapper.map(json, rules);

        assertEquals("only-one", claims.get("user_tags"));
    }

    @Test
    void simpleFieldArrayOfStringsMultiple() {
        String json = """
                {"tags": ["a", "b", "c"]}
                """;
        List<MappingRule> rules = List.of(new MappingRule("tags", "user_tags"));
        Map<String, Object> claims = JsonPathMapper.map(json, rules);

        assertEquals(List.of("a", "b", "c"), claims.get("user_tags"));
    }

    @Test
    void jsonPathScalar() {
        String json = """
                {"data": {"user": {"name": "John"}}}
                """;
        List<MappingRule> rules = List.of(new MappingRule("$.data.user.name", "user_name"));
        Map<String, Object> claims = JsonPathMapper.map(json, rules);

        assertEquals("John", claims.get("user_name"));
    }

    @Test
    void jsonPathArrayOfStringsCollapseSingle() {
        String json = """
                {"data": {"groups": ["admins"]}}
                """;
        List<MappingRule> rules = List.of(new MappingRule("$.data.groups[0]", "group"));
        Map<String, Object> claims = JsonPathMapper.map(json, rules);

        assertEquals("admins", claims.get("group"));
    }

    @Test
    void jsonPathArrayOfStringsMultiple() {
        String json = """
                {"data": {"groups": ["admins", "devs", "ops"]}}
                """;
        List<MappingRule> rules = List.of(new MappingRule("$.data.groups", "groups"));
        Map<String, Object> claims = JsonPathMapper.map(json, rules);

        assertEquals(List.of("admins", "devs", "ops"), claims.get("groups"));
    }

    // ── Structured mode (json: prefix) ───────────────────────────────────────

    @Test
    void jsonPrefixJsonPathArrayOfObjects() {
        String json = """
                {
                  "data": {
                    "ldapUser": [
                      {"cn": "John", "mail": "john@example.com"},
                      {"cn": "Jane", "mail": "jane@example.com"}
                    ]
                  }
                }
                """;
        List<MappingRule> rules = List.of(new MappingRule("$.data.ldapUser", "json:users"));
        Map<String, Object> claims = JsonPathMapper.map(json, rules);

        Object value = claims.get("users");
        assertInstanceOf(List.class, value);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) value;
        assertEquals(2, users.size());
        assertEquals("John", users.get(0).get("cn"));
        assertEquals("jane@example.com", users.get(1).get("mail"));
    }

    @Test
    void jsonPrefixJsonPathSingleElementArrayNotCollapsed() {
        String json = """
                {"data": {"items": ["only-one"]}}
                """;
        List<MappingRule> rules = List.of(new MappingRule("$.data.items", "json:items"));
        Map<String, Object> claims = JsonPathMapper.map(json, rules);

        Object value = claims.get("items");
        assertInstanceOf(List.class, value);
        assertEquals(List.of("only-one"), value);
    }

    @Test
    void jsonPrefixSimpleFieldArrayOfObjects() {
        String json = """
                {
                  "users": [
                    {"id": 1, "name": "Alice"},
                    {"id": 2, "name": "Bob"}
                  ]
                }
                """;
        List<MappingRule> rules = List.of(new MappingRule("users", "json:all_users"));
        Map<String, Object> claims = JsonPathMapper.map(json, rules);

        Object value = claims.get("all_users");
        assertInstanceOf(List.class, value);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) value;
        assertEquals(2, users.size());
        assertEquals(1, users.get(0).get("id"));
        assertEquals("Bob", users.get(1).get("name"));
    }

    @Test
    void jsonPrefixScalarPassedThrough() {
        String json = """
                {"data": {"count": 42}}
                """;
        List<MappingRule> rules = List.of(new MappingRule("$.data.count", "json:total"));
        Map<String, Object> claims = JsonPathMapper.map(json, rules);

        assertEquals(42, claims.get("total"));
    }

    @Test
    void jsonPrefixNestedObjects() {
        String json = """
                {
                  "data": {
                    "ldapUser": [
                      {
                        "cn": "John",
                        "memberOf": ["group1", "group2"],
                        "title": {"org": "Engineering"}
                      }
                    ]
                  }
                }
                """;
        List<MappingRule> rules = List.of(new MappingRule("$.data.ldapUser", "json:users"));
        Map<String, Object> claims = JsonPathMapper.map(json, rules);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) claims.get("users");
        assertEquals(1, users.size());
        assertEquals(List.of("group1", "group2"), users.get(0).get("memberOf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> title = (Map<String, Object>) users.get(0).get("title");
        assertEquals("Engineering", title.get("org"));
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test
    void nullJsonReturnsEmpty() {
        Map<String, Object> claims = JsonPathMapper.map(null, List.of(new MappingRule("x", "y")));
        assertTrue(claims.isEmpty());
    }

    @Test
    void nullRulesReturnsEmpty() {
        Map<String, Object> claims = JsonPathMapper.map("{\"x\":1}", null);
        assertTrue(claims.isEmpty());
    }

    @Test
    void missingFieldReturnsEmpty() {
        String json = """
                {"role": "admin"}
                """;
        List<MappingRule> rules = List.of(new MappingRule("nonexistent", "claim"));
        Map<String, Object> claims = JsonPathMapper.map(json, rules);

        assertNull(claims.get("claim"));
    }

    @Test
    void missingJsonPathReturnsEmpty() {
        String json = """
                {"data": {"x": 1}}
                """;
        List<MappingRule> rules = List.of(new MappingRule("$.data.missing.path", "claim"));
        Map<String, Object> claims = JsonPathMapper.map(json, rules);

        assertNull(claims.get("claim"));
    }
}
