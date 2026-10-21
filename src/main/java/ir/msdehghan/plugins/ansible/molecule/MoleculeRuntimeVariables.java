package ir.msdehghan.plugins.ansible.molecule;

import com.intellij.lang.documentation.DocumentationMarkup;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/** Molecule variables injected into the inventory for scenario playbooks. */
public final class MoleculeRuntimeVariables {
    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("molecule_ephemeral_directory", "Path to the temporary directory for this Molecule scenario. Molecule also exposes it as MOLECULE_EPHEMERAL_DIRECTORY."),
            Map.entry("molecule_file", "Path to the molecule.yml configuration file for this scenario."),
            Map.entry("molecule_scenario_directory", "Path to this scenario's directory, which contains molecule.yml and its scenario playbooks."),
            Map.entry("molecule_project_directory", "Path to the Molecule project directory."),
            Map.entry("molecule_scenario_name", "Name of the active Molecule scenario."),
            Map.entry("molecule_instance_config", "Path to Molecule's instance configuration file for this scenario."),
            Map.entry("molecule_inventory_file", "Path to the inventory generated for the active Molecule scenario."),
            Map.entry("molecule_state_file", "Path to Molecule's state file for the active scenario."));

    private MoleculeRuntimeVariables() { }

    public static boolean contains(String name) {
        return DESCRIPTIONS.containsKey(name);
    }

    public static Set<String> names() {
        return DESCRIPTIONS.keySet();
    }

    public static @Nullable String description(String name) {
        return DESCRIPTIONS.get(name);
    }

    public static String documentation(String name) {
        String description = description(name);
        if (description == null) return null;
        return DocumentationMarkup.DEFINITION_START + "Molecule runtime variable" + DocumentationMarkup.DEFINITION_END
                + DocumentationMarkup.CONTENT_START + description + DocumentationMarkup.CONTENT_END;
    }
}
