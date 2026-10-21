package ir.msdehghan.plugins.ansible;

import com.intellij.lang.documentation.DocumentationMarkup;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Built-in Ansible magic variables documented by Ansible. */
public final class AnsibleMagicVariables {
    private static final Map<String, String> DESCRIPTIONS;

    static {
        Map<String, String> descriptions = new LinkedHashMap<>();
        descriptions.put("ansible_check_mode", "Boolean indicating whether the play is running in check mode.");
        descriptions.put("ansible_collection_name", "Fully qualified name of the collection containing the current task, in namespace.collection format.");
        descriptions.put("ansible_config_file", "Path to the Ansible configuration file used for this run.");
        descriptions.put("ansible_dependent_role_names", "Names of roles imported into the current play as dependencies of other roles.");
        descriptions.put("ansible_diff_mode", "Boolean indicating whether the play is running in diff mode.");
        descriptions.put("ansible_forks", "Maximum number of forks available to this Ansible run.");
        descriptions.put("ansible_index_var", "Name of the variable configured by loop_control.index_var.");
        descriptions.put("ansible_inventory_sources", "Inventory sources used for this Ansible run.");
        descriptions.put("ansible_limit", "Value of the --limit option for this Ansible run.");
        descriptions.put("ansible_loop", "Map containing extended loop information when loop_control.extended is enabled.");
        descriptions.put("ansible_loop_var", "Name of the variable configured by loop_control.loop_var.");
        descriptions.put("ansible_parent_role_names", "Parent roles of the current role, with the most recent parent first.");
        descriptions.put("ansible_parent_role_paths", "Paths of parent roles of the current role, with the most recent parent first.");
        descriptions.put("ansible_play_batch", "Active hosts in the current play batch. Failed and unreachable hosts are excluded.");
        descriptions.put("ansible_play_hosts", "Hosts in the current play, excluding failed and unreachable hosts.");
        descriptions.put("ansible_play_hosts_all", "All hosts targeted by the current play.");
        descriptions.put("ansible_play_name", "Name of the currently executed play.");
        descriptions.put("ansible_play_role_names", "Roles explicitly imported into the current play, excluding roles included through dependencies.");
        descriptions.put("ansible_playbook_python", "Path to the Python interpreter used by Ansible on the control node.");
        descriptions.put("ansible_role_name", "Fully qualified collection role name in namespace.collection.role_name format.");
        descriptions.put("ansible_role_names", "Roles imported into the current play, including roles referenced as dependencies.");
        descriptions.put("ansible_run_tags", "Tags included in this run by the --tags option. Defaults to [all] when --tags is omitted.");
        descriptions.put("ansible_search_path", "Current search path for action plugins and lookups, including relative template paths.");
        descriptions.put("ansible_skip_tags", "Tags excluded from this run by the --skip-tags option.");
        descriptions.put("ansible_verbosity", "Verbosity level configured for this Ansible run.");
        descriptions.put("ansible_version", "Map describing the running Ansible version, including full, major, minor, revision and string.");
        descriptions.put("group_names", "Inventory groups containing the current inventory_hostname. Delegation does not change this value.");
        descriptions.put("groups", "Map of inventory groups to the hosts that belong to each group.");
        descriptions.put("hostvars", "Map of inventory hosts to the variables assigned to each host.");
        descriptions.put("inventory_dir", "Directory of the inventory source where inventory_hostname was first defined.");
        descriptions.put("inventory_hostname", "Inventory name of the current host being processed.");
        descriptions.put("inventory_hostname_short", "Short form of inventory_hostname, derived from the host name before its first dot.");
        descriptions.put("inventory_file", "Inventory source file where inventory_hostname was first defined.");
        descriptions.put("omit", "Special value used to omit a task option, commonly with the default filter.");
        descriptions.put("play_hosts", "Deprecated alias for ansible_play_batch.");
        descriptions.put("playbook_dir", "Path to the directory of the playbook currently being executed. This can differ from the directory of the playbook passed to ansible-playbook when import_playbook is used.");
        descriptions.put("role_name", "Name of the role currently being executed.");
        descriptions.put("role_names", "Deprecated alias for ansible_play_role_names.");
        descriptions.put("role_path", "Path to the directory of the role currently being executed.");
        DESCRIPTIONS = Collections.unmodifiableMap(descriptions);
    }

    private AnsibleMagicVariables() { }

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
        return DocumentationMarkup.DEFINITION_START + "Ansible magic variable" + DocumentationMarkup.DEFINITION_END
                + DocumentationMarkup.CONTENT_START + description + DocumentationMarkup.CONTENT_END;
    }
}
