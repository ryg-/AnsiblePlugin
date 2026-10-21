package ir.msdehghan.plugins.ansible.molecule;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.FileTypeIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.YAMLFileType;
import org.jetbrains.yaml.psi.YAMLDocument;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLMapping;
import org.jetbrains.yaml.psi.YAMLScalar;
import org.jetbrains.yaml.psi.YAMLSequence;
import org.jetbrains.yaml.psi.YAMLSequenceItem;
import org.jetbrains.yaml.psi.YAMLValue;
import org.jetbrains.yaml.psi.YAMLFile;
import ir.msdehghan.plugins.ansible.reference.RoleRootResolver;
import ir.msdehghan.plugins.ansible.reference.AnsibleYamlUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.nio.file.Path;

/** Project-scoped discovery of Molecule scenario directories and their statically configured playbooks. */
public final class MoleculeScenarioCatalog {
    private static final Key<CachedCatalog> CACHE = Key.create("ansible.molecule.scenario.catalog");
    private static final List<String> DEFAULT_PLAYBOOKS = List.of(
            "prepare.yml", "converge.yml", "side_effect.yml", "verify.yml", "cleanup.yml");

    private final Project project;
    private final VirtualFile projectRoot;
    private final Path projectRootPath;

    private MoleculeScenarioCatalog(Project project) {
        this.project = project;
        this.projectRootPath = project.getBasePath() == null ? null
                : Path.of(project.getBasePath()).toAbsolutePath().normalize();
        this.projectRoot = projectRootPath == null ? null
                : LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectRootPath);
    }

    public static @NotNull MoleculeScenarioCatalog forProject(@NotNull Project project) {
        return new MoleculeScenarioCatalog(project);
    }

    public @NotNull List<Scenario> getScenarios() {
        if (projectRoot == null) return List.of();
        long modificationCount = VirtualFileManager.getInstance().getModificationCount();
        long psiModificationCount = PsiModificationTracker.getInstance(project).getModificationCount();
        CachedCatalog cached = project.getUserData(CACHE);
        if (cached != null && cached.modificationCount == modificationCount
                && cached.psiModificationCount == psiModificationCount) return cached.scenarios;
        List<Scenario> scenarios = discover();
        project.putUserData(CACHE, new CachedCatalog(modificationCount, psiModificationCount, scenarios));
        return scenarios;
    }

    private List<Scenario> discover() {
        Set<VirtualFile> yamlFiles = new LinkedHashSet<>(FileTypeIndex.getFiles(YAMLFileType.YML, GlobalSearchScope.projectScope(project)));
        collectScenarioConfigs(projectRoot, yamlFiles);
        List<Scenario> scenarios = new ArrayList<>();
        for (VirtualFile file : yamlFiles) {
            if (!(file.getName().equals("molecule.yml") || file.getName().equals("molecule.yaml"))) continue;
            VirtualFile scenarioDirectory = file.getParent();
            if (scenarioDirectory == null || !isSupportedScenarioPath(scenarioDirectory)) continue;
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (!(psiFile instanceof YAMLFile yamlFile) || !hasMappingRoot(yamlFile)) continue;
            List<PsiFile> playbooks = resolvePlaybooks(scenarioDirectory, yamlFile);
            List<VirtualFile> roles = new ArrayList<>(roleLocalDirectory(scenarioDirectory));
            for (PsiFile playbook : playbooks) {
                for (VirtualFile role : resolveInvokedRoles((YAMLFile) playbook)) {
                    if (!roles.contains(role)) roles.add(role);
                }
            }
            scenarios.add(new Scenario(scenarioDirectory, file, psiFile, playbooks, roles));
        }
        scenarios.sort(Comparator.comparing(scenario -> scenario.directory.toString()));
        return List.copyOf(scenarios);
    }

    private static List<VirtualFile> roleLocalDirectory(VirtualFile scenarioDirectory) {
        VirtualFile moleculeRoot = scenarioDirectory.getParent();
        if (moleculeRoot == null || !moleculeRoot.getName().equals("molecule")) return List.of();
        VirtualFile roleDirectory = moleculeRoot.getParent();
        if (roleDirectory == null || roleDirectory.getParent() == null) return List.of();
        String[] path = roleDirectory.getPath().split("/");
        for (int i = 0; i + 1 < path.length; i++) {
            if (path[i].equals("roles") && path[i + 1].equals(roleDirectory.getName())) return List.of(roleDirectory);
        }
        return List.of();
    }

    private List<VirtualFile> resolveInvokedRoles(YAMLFile playbook) {
        Set<String> names = new LinkedHashSet<>();
        for (YAMLKeyValue pair : PsiTreeUtil.findChildrenOfType(playbook, YAMLKeyValue.class)) {
            String key = pair.getKeyText();
            if (key.equals("roles") && pair.getValue() instanceof YAMLSequence sequence) {
                for (YAMLSequenceItem item : sequence.getItems()) {
                    if (item.getValue() instanceof YAMLScalar scalar) addStaticRole(scalar.getTextValue(), names);
                    else if (item.getValue() instanceof YAMLMapping mapping) {
                        YAMLValue role = AnsibleYamlUtil.value(mapping, "role");
                        if (role instanceof YAMLScalar scalar) addStaticRole(scalar.getTextValue(), names);
                    }
                }
            } else if (key.equals("include_role") || key.equals("import_role")
                    || key.equals("ansible.builtin.include_role") || key.equals("ansible.builtin.import_role")) {
                if (pair.getValue() instanceof YAMLMapping mapping) {
                    YAMLValue name = AnsibleYamlUtil.value(mapping, "name");
                    if (name instanceof YAMLScalar scalar) addStaticRole(scalar.getTextValue(), names);
                }
            }
        }
        List<VirtualFile> roles = new ArrayList<>();
        RoleRootResolver resolver = RoleRootResolver.forProject(project);
        Path playbookPath = playbook.getVirtualFile() == null ? null : Path.of(playbook.getVirtualFile().getPath());
        for (String name : names) roles.addAll(resolver.resolveVirtual(name, playbookPath));
        return List.copyOf(roles);
    }

    private static void addStaticRole(String role, Set<String> names) {
        if (role != null && !role.isBlank() && !isDynamic(role)) names.add(role.trim());
    }

    private static void collectScenarioConfigs(VirtualFile root, Set<VirtualFile> yamlFiles) {
        if (root == null) return;
        addScenarioConfigs(root.findChild("molecule"), yamlFiles);
        collectRoleScenarioConfigs(root.findChild("roles"), yamlFiles);
        collectCollectionScenarioConfigs(root.findChild("ansible_collections"), yamlFiles);
        VirtualFile collections = root.findChild("collections");
        if (collections != null) collectCollectionScenarioConfigs(collections.findChild("ansible_collections"), yamlFiles);
    }

    private static void addScenarioConfigs(VirtualFile moleculeRoot, Set<VirtualFile> yamlFiles) {
        if (moleculeRoot == null || !moleculeRoot.isDirectory()) return;
        for (VirtualFile scenario : moleculeRoot.getChildren()) {
            if (scenario.isDirectory()) addConfig(scenario, yamlFiles);
        }
    }

    private static void addConfig(VirtualFile scenarioDirectory, Set<VirtualFile> yamlFiles) {
        for (String name : List.of("molecule.yml", "molecule.yaml")) {
            VirtualFile config = scenarioDirectory.findChild(name);
            if (config != null && !config.isDirectory()) yamlFiles.add(config);
        }
    }

    private static void collectRoleScenarioConfigs(VirtualFile rolesRoot, Set<VirtualFile> yamlFiles) {
        if (rolesRoot == null || !rolesRoot.isDirectory()) return;
        for (VirtualFile role : rolesRoot.getChildren()) {
            if (role.isDirectory()) addScenarioConfigs(role.findChild("molecule"), yamlFiles);
        }
    }

    private static void collectCollectionScenarioConfigs(VirtualFile collectionsRoot, Set<VirtualFile> yamlFiles) {
        if (collectionsRoot == null || !collectionsRoot.isDirectory()) return;
        for (VirtualFile namespace : collectionsRoot.getChildren()) {
            if (!namespace.isDirectory()) continue;
            for (VirtualFile collection : namespace.getChildren()) {
                if (!collection.isDirectory()) continue;
                VirtualFile extensions = collection.findChild("extensions");
                addScenarioConfigs(extensions == null ? null : extensions.findChild("molecule"), yamlFiles);
                collectRoleScenarioConfigs(collection.findChild("roles"), yamlFiles);
            }
        }
    }

    private boolean isSupportedScenarioPath(VirtualFile scenarioDirectory) {
        if (projectRoot == null || !scenarioDirectory.getPath().startsWith(projectRoot.getPath() + "/")) return false;
        String relative = scenarioDirectory.getPath().substring(projectRoot.getPath().length() + 1);
        List<String> parts = List.of(relative.split("/"));
        if (parts.size() < 2) return false;
        int last = parts.size() - 2;
        if (!parts.get(last).equals("molecule")) return false;
        if (last == 0) return true; // <project>/molecule/<scenario>
        if (parts.get(last - 1).equals("extensions")) return true; // <collection>/extensions/molecule/<scenario>
        for (int i = 0; i < last; i++) {
            if (parts.get(i).equals("roles") && i + 1 < last) return true; // <role>/molecule/<scenario>
        }
        return false;
    }

    private static boolean hasMappingRoot(YAMLFile file) {
        return !file.getDocuments().isEmpty()
                && file.getDocuments().getFirst().getTopLevelValue() instanceof YAMLMapping;
    }

    private List<PsiFile> resolvePlaybooks(VirtualFile scenarioDirectory, YAMLFile config) {
        LinkedHashSet<VirtualFile> roots = new LinkedHashSet<>();
        boolean configured = false;
        for (YAMLDocument document : config.getDocuments()) {
            if (!(document.getTopLevelValue() instanceof YAMLMapping mapping)) continue;
            configured |= addConfiguredPaths(mapping, "ansible", "playbooks", scenarioDirectory, roots);
            configured |= addConfiguredPaths(mapping, "provisioner", "playbooks", scenarioDirectory, roots);
        }
        if (!configured) {
            for (String defaultName : DEFAULT_PLAYBOOKS) {
                VirtualFile candidate = scenarioDirectory.findChild(defaultName);
                if (candidate != null && !candidate.isDirectory()) roots.add(candidate);
            }
        }
        Deque<VirtualFile> pending = new ArrayDeque<>(roots);
        LinkedHashSet<VirtualFile> visited = new LinkedHashSet<>();
        List<PsiFile> result = new ArrayList<>();
        while (!pending.isEmpty()) {
            VirtualFile virtualFile = pending.removeFirst();
            if (!virtualFile.getPath().startsWith(projectRoot.getPath() + "/") || !visited.add(virtualFile)
                    || virtualFile.isDirectory()) continue;
            PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
            if (!(psiFile instanceof YAMLFile yamlFile)) continue;
            result.add(psiFile);
            collectImports(yamlFile, virtualFile.getParent(), pending);
        }
        return List.copyOf(result);
    }

    private boolean addConfiguredPaths(YAMLMapping root, String sectionName, String playbooksName,
                                       VirtualFile scenarioDirectory, Set<VirtualFile> result) {
        YAMLValue sectionValue = AnsibleYamlUtil.value(root, sectionName);
        if (!(sectionValue instanceof YAMLMapping section)) return false;
        YAMLValue playbooks = AnsibleYamlUtil.value(section, playbooksName);
        if (playbooks == null) return false;
        if (playbooks instanceof YAMLScalar scalar) addStaticPath(scalar.getTextValue(), scenarioDirectory, result);
        else if (playbooks instanceof YAMLSequence sequence) {
            for (YAMLSequenceItem item : sequence.getItems()) {
                if (item.getValue() instanceof YAMLScalar scalar) addStaticPath(scalar.getTextValue(), scenarioDirectory, result);
            }
        } else if (playbooks instanceof YAMLMapping mapping) {
            for (YAMLKeyValue playbook : mapping.getKeyValues()) {
                if (playbook.getValue() instanceof YAMLScalar scalar) {
                    addStaticPath(scalar.getTextValue(), scenarioDirectory, result);
                }
            }
        }
        return true;
    }

    private void addStaticPath(String value, VirtualFile scenarioDirectory, Set<VirtualFile> paths) {
        if (value == null || value.isBlank() || isDynamic(value)) return;
        if (value.trim().startsWith("/")) return;
        VirtualFile target = scenarioDirectory.findFileByRelativePath(value.trim());
        if (target != null) paths.add(target);
    }

    private void collectImports(YAMLFile file, VirtualFile containingDirectory, Deque<VirtualFile> pending) {
        for (YAMLDocument document : file.getDocuments()) {
            if (!(document.getTopLevelValue() instanceof YAMLSequence sequence)) continue;
            for (YAMLSequenceItem item : sequence.getItems()) {
                if (!(item.getValue() instanceof YAMLMapping play)) continue;
                YAMLValue importValue = AnsibleYamlUtil.value(play, "import_playbook");
                if (importValue instanceof YAMLScalar scalar && !isDynamic(scalar.getTextValue())) {
                    String importPath = scalar.getTextValue().trim();
                    if (!importPath.startsWith("/")) {
                        VirtualFile imported = containingDirectory.findFileByRelativePath(importPath);
                        if (imported != null) pending.add(imported);
                    }
                }
            }
        }
    }

    private static boolean isDynamic(String value) {
        return value.contains("{{") || value.contains("}}") || value.contains("{%") || value.contains("%}");
    }

    public static @NotNull List<Scenario> scenariosForRole(@NotNull Project project, @NotNull VirtualFile roleDirectory) {
        return forProject(project).getScenarios().stream()
                .filter(scenario -> scenario.roles.contains(roleDirectory))
                .toList();
    }

    public static @NotNull List<Scenario> scenariosForPlaybook(@NotNull Project project, @NotNull PsiFile playbook) {
        VirtualFile target = playbook.getVirtualFile();
        if (target == null) return List.of();
        return forProject(project).getScenarios().stream()
                .filter(scenario -> scenario.playbooks.stream().anyMatch(candidate -> {
                    VirtualFile candidateFile = candidate.getVirtualFile();
                    return target.equals(candidateFile);
                }))
                .toList();
    }

    public record Scenario(VirtualFile directory, VirtualFile configFile, PsiFile config,
                           List<PsiFile> playbooks, List<VirtualFile> roles) {
        public Scenario {
            playbooks = List.copyOf(playbooks);
            roles = List.copyOf(roles);
        }

        public String name() {
            return directory.getName();
        }
    }

    private record CachedCatalog(long modificationCount, long psiModificationCount, List<Scenario> scenarios) { }
}
