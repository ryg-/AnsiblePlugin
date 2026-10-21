import api.AnsibleModuleDto;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ExtractDocTest {
    @Test
    public void rejectsModuleNamesThatCouldEscapeOutputDirectory() {
        Path outputDirectory = Path.of("build", "generated-docs").toAbsolutePath().normalize();

        assertThrows(IOException.class, () -> ExtractDoc.outputPath(outputDirectory, "../outside"));
        assertThrows(IOException.class, () -> ExtractDoc.outputPath(outputDirectory, "vendor\\..\\outside"));
    }

    @Test
    public void serializesDefaultValuesLikeTheRuntimeParser() throws Exception {
        String json = """
                {"vendor.storage.snapshot":{"doc":{"module":"vendor.storage.snapshot","options":{
                  "path":{"default":"/tmp/snapshot"},
                  "labels":{"default":["primary","backup"]},
                  "config":{"default":{"enabled":true}}
                }}}}
                """;

        AnsibleModuleDto dto = ExtractDoc.convert("vendor.storage.snapshot", json);

        assertEquals("/tmp/snapshot", dto.fields.get("path").defaultValue);
        assertEquals("primary\nbackup", dto.fields.get("labels").defaultValue);
        assertEquals("{\"enabled\":true}", dto.fields.get("config").defaultValue);
    }
}
