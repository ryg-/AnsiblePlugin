package ir.msdehghan.plugins.ansible.model.ansible.task;

import junit.framework.TestCase;

import java.io.IOException;
import java.util.Map;

public class AnsibleModuleParserTest extends TestCase {
    public void testParsesFqcnListingAndKeepsAmbiguousShortNamesDistinct() throws Exception {
        Map<String, String> listing = AnsibleModuleParser.parseListing("""
                {"ansible.builtin.copy":"Copy files", "vendor.storage.copy":"Copy storage", "vendor.storage.snapshot":"Create snapshot"}
                """);

        assertEquals(3, listing.size());
        assertTrue(listing.containsKey("ansible.builtin.copy"));
        assertTrue(listing.containsKey("vendor.storage.copy"));
    }

    public void testParsesDetailsWithOptionalFieldsAndListText() throws Exception {
        AnsibleModuleDto dto = AnsibleModuleParser.parseDetail("vendor.storage.snapshot", """
                {"vendor.storage.snapshot":{"doc":{"module":"vendor.storage.snapshot","short_description":"Create snapshot",
                "description":["Creates a snapshot", "for a volume"],"examples":"- name: Snapshot\\n  vendor.storage.snapshot:",
                "options":{"volume":{"description":["Volume identifier"],"type":"str","required":true,
                "aliases":["volume_id"],"version_added":"1.2"}},"deprecated":{"why":"old","removed_in":"9.0"}},
                "return":{"snapshot_id":{"description":["The snapshot identifier"]}}}}
                """);

        assertEquals("vendor.storage.snapshot", dto.name);
        assertEquals("Creates a snapshot\nfor a volume", dto.description);
        assertTrue(dto.examples.contains("vendor.storage.snapshot"));
        assertEquals("The snapshot identifier", dto.returnDocs.get("snapshot_id"));
        assertTrue(dto.fields.get("volume").required);
        assertEquals("volume_id", dto.fields.get("volume").aliases.getFirst());
        assertEquals("9.0", dto.deprecated.removedIn);
    }

    public void testPreservesMappingValuedOptionDefault() throws Exception {
        AnsibleModuleDto dto = AnsibleModuleParser.parseDetail("vendor.example.module", """
                {"vendor.example.module":{"doc":{"module":"vendor.example.module","options":{
                  "settings":{"default":{"retries":3,"enabled":true}}
                }}}}
                """);

        assertEquals("{\"retries\":3,\"enabled\":true}", dto.fields.get("settings").defaultValue);
    }

    public void testRejectsMalformedAndUnexpectedDetailShapes() {
        assertIOException(() -> AnsibleModuleParser.parseListing("[]"));
        assertIOException(() -> AnsibleModuleParser.parseDetail("copy", "{}"));
        assertIOException(() -> AnsibleModuleParser.parseDetail("copy", "not-json"));
    }

    private static void assertIOException(ThrowingOperation operation) {
        try {
            operation.run();
            fail("Expected parser to reject input");
        } catch (IOException expected) {
            // Expected.
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
