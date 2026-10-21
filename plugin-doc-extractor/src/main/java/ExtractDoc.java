import api.AnsibleModuleDto;
import api.AnsibleModuleDto.Field;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Generates optional documentation snapshots from the Ansible installation on this machine. */
public final class ExtractDoc {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExtractDoc() { }

    public static void main(String[] args) throws IOException, InterruptedException {
        String executable = args.length > 0 ? args[0] : "ansible-doc";
        Path outputDirectory = args.length > 1 ? Path.of(args[1]) : Path.of("docs");
        Map<String, String> modules = parseListing(run(executable, "-t", "module", "-l", "--json"));
        Path normalizedOutputDirectory = outputDirectory.toAbsolutePath().normalize();
        Files.createDirectories(normalizedOutputDirectory);
        for (String fqcn : modules.keySet()) {
            AnsibleModuleDto dto = convert(fqcn, run(executable, "-t", "module", "-j", fqcn));
            Path output = outputPath(normalizedOutputDirectory, fqcn);
            Files.createDirectories(output.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), dto);
        }
    }

    static Path outputPath(Path outputDirectory, String fqcn) throws IOException {
        if (fqcn == null || fqcn.isBlank() || fqcn.contains("/") || fqcn.contains("\\")) {
            throw new IOException("Invalid module name in ansible-doc listing: " + fqcn);
        }
        Path root = outputDirectory.toAbsolutePath().normalize();
        Path output = root.resolve(fqcn + ".json").normalize();
        if (!output.startsWith(root)) {
            throw new IOException("Module output path escapes the output directory: " + fqcn);
        }
        return output;
    }

    static Map<String, String> parseListing(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        if (root == null || !root.isObject()) throw new IOException("ansible-doc listing must be a JSON object");
        Map<String, String> modules = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> modules.put(entry.getKey(), text(entry.getValue())));
        return modules;
    }

    static AnsibleModuleDto convert(String fqcn, String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        if (root == null || !root.isObject() || !root.has(fqcn)) {
            throw new IOException("ansible-doc detail did not contain module " + fqcn);
        }
        JsonNode module = root.get(fqcn);
        JsonNode doc = module.path("doc");
        if (!doc.isObject()) throw new IOException("ansible-doc detail has no doc object for " + fqcn);

        AnsibleModuleDto dto = new AnsibleModuleDto();
        dto.name = fqcn;
        dto.category = text(doc.path("category"));
        dto.addedIn = text(doc.path("version_added"));
        dto.shortDescription = text(doc.path("short_description"));
        dto.description = text(doc.path("description"));
        dto.requirements = text(doc.path("requirements"));
        dto.notes = text(doc.path("notes"));
        dto.examples = text(doc.path("examples"));

        JsonNode deprecated = doc.path("deprecated");
        if (deprecated.isObject()) {
            dto.deprecated = new AnsibleModuleDto.Deprecated();
            dto.deprecated.alternative = text(deprecated.path("alternative"));
            dto.deprecated.why = text(deprecated.path("why"));
            dto.deprecated.removedIn = text(deprecated.path("removed_in"));
        }

        JsonNode returns = module.path("return");
        if (returns.isObject()) {
            dto.returnFields = new LinkedHashSet<>();
            dto.returnDocs = new LinkedHashMap<>();
            returns.fields().forEachRemaining(entry -> {
                dto.returnFields.add(entry.getKey());
                dto.returnDocs.put(entry.getKey(), text(entry.getValue().path("description")));
            });
        }

        JsonNode options = doc.path("options");
        if (options.isObject()) {
            dto.fields = new LinkedHashMap<>();
            options.fields().forEachRemaining(entry -> dto.fields.put(entry.getKey(), convertField(entry.getValue())));
        }
        return dto;
    }

    private static Field convertField(JsonNode option) {
        Field field = new Field();
        field.aliases = strings(option.path("aliases"));
        field.choices = strings(option.path("choices"));
        field.description = text(option.path("description"));
        field.elements = text(option.path("elements"));
        field.required = option.path("required").asBoolean(false);
        field.type = text(option.path("type"));
        field.versionAdded = text(option.path("version_added"));
        JsonNode defaultValue = option.path("default");
        field.defaultValue = defaultValue.isObject() ? defaultValue.toString() : text(defaultValue);
        return field;
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(value -> { if (value.isTextual()) values.add(value.asText()); });
        return values;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isTextual() || node.isNumber() || node.isBoolean()) return node.asText();
        if (node.isArray()) {
            List<String> lines = new ArrayList<>();
            node.forEach(value -> {
                String line = text(value);
                if (line != null && !line.isBlank()) lines.add(line);
            });
            return lines.isEmpty() ? null : String.join("\n", lines);
        }
        return null;
    }

    private static String run(String executable, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new IOException("Command failed (" + exitCode + "): " + String.join(" ", command) + "\n" + output);
        return output;
    }
}
