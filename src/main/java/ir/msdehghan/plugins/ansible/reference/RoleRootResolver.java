package ir.msdehghan.plugins.ansible.reference;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves role directories using the project roles/ directory and roles_path from ansible.cfg. */
public final class RoleRootResolver {
    private static final Key<RoleRootResolver> PROJECT_RESOLVER = Key.create("ansible.role.root.resolver");
    private final Path projectRoot;
    private final Path configFile;
    private final long vfsModificationCount;
    private final List<Path> roleRoots;

    public RoleRootResolver(@NotNull Project project) {
        String basePath = project.getBasePath();
        this.projectRoot = basePath == null ? null : normalizePath(Path.of(basePath));
        this.configFile = findConfig(projectRoot);
        this.vfsModificationCount = VirtualFileManager.getInstance().getModificationCount();
        this.roleRoots = buildRoleRoots(projectRoot, configFile);
    }

    RoleRootResolver(Path projectRoot, Path configFile) {
        this(projectRoot, configFile, -1);
    }

    private RoleRootResolver(Path projectRoot, Path configFile, long vfsModificationCount) {
        this.projectRoot = projectRoot == null ? null : normalizePath(projectRoot);
        this.configFile = configFile == null ? null : normalizePath(configFile);
        this.vfsModificationCount = vfsModificationCount;
        this.roleRoots = buildRoleRoots(this.projectRoot, configFile);
    }

    public static RoleRootResolver forProject(Project project) {
        RoleRootResolver cached = project.getUserData(PROJECT_RESOLVER);
        Path root = project.getBasePath() == null ? null : normalizePath(Path.of(project.getBasePath()));
        long vfsModificationCount = VirtualFileManager.getInstance().getModificationCount();
        if (cached != null && java.util.Objects.equals(cached.projectRoot, root)
                && cached.vfsModificationCount == vfsModificationCount) return cached;
        Path config = findConfig(root);
        RoleRootResolver resolver = new RoleRootResolver(root, config, vfsModificationCount);
        project.putUserData(PROJECT_RESOLVER, resolver);
        return resolver;
    }

    public List<Path> getRoleRoots() {
        return roleRoots;
    }

    /** Returns project roots plus the role root relevant to a playbook or role file. */
    public List<Path> getRoleRoots(Path contextFile) {
        if (projectRoot == null || contextFile == null) return roleRoots;
        Path context = normalizePath(contextFile);
        Path directory = Files.isDirectory(context) ? context : context.getParent();
        if (directory == null || !directory.startsWith(projectRoot)) return roleRoots;

        Set<Path> roots = new LinkedHashSet<>(roleRoots);
        Path contextConfig = findConfig(directory);
        if (contextConfig != null) addConfiguredRoleRoots(roots, contextConfig);

        for (Path current = directory; current != null && current.startsWith(projectRoot); current = current.getParent()) {
            if (current.getFileName() != null && current.getFileName().toString().equals("roles")) {
                roots.add(normalizePath(current));
                break;
            }
            Path adjacentRoles = current.resolve("roles").normalize();
            if (Files.isDirectory(adjacentRoles)) {
                roots.add(normalizePath(adjacentRoles));
                break;
            }
        }
        return List.copyOf(roots);
    }

    private static List<Path> buildRoleRoots(Path projectRoot, Path configFile) {
        if (projectRoot == null) return List.of();
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(projectRoot.resolve("roles").normalize());
        if (configFile != null) {
            String rolesPath = readRolesPath(configFile);
            if (rolesPath != null) {
                Path configDirectory = configFile.getParent();
                for (String configuredRoot : rolesPath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                    String value = configuredRoot.trim();
                    if (value.isEmpty()) continue;
                    Path root = Path.of(value);
                    roots.add(normalizePath(root.isAbsolute() ? root : configDirectory.resolve(root)));
                }
            }
        }
        return List.copyOf(roots);
    }

    public List<Path> resolve(String roleName) {
        return resolve(roleName, null);
    }

    public List<Path> resolve(String roleName, Path contextFile) {
        if (roleName == null || roleName.isBlank() || roleName.contains("..") || roleName.startsWith("/")) {
            return List.of();
        }
        String[] collectionName = roleName.split("\\.");
        if (collectionName.length == 3 && projectRoot != null
                && java.util.Arrays.stream(collectionName).noneMatch(String::isBlank)) {
            List<Path> collectionMatches = new ArrayList<>();
            for (Path collectionRoot : collectionRoots(projectRoot)) {
                Path candidate = collectionRoot.resolve(collectionName[0]).resolve(collectionName[1])
                        .resolve("roles").resolve(collectionName[2]).normalize();
                if (candidate.startsWith(collectionRoot) && Files.isDirectory(candidate)) collectionMatches.add(candidate);
            }
            if (!collectionMatches.isEmpty()) return List.copyOf(collectionMatches);
        }
        List<Path> matches = new ArrayList<>();
        for (Path root : getRoleRoots(contextFile)) {
            Path candidate = root.resolve(roleName).normalize();
            if (candidate.startsWith(root) && Files.isDirectory(candidate)) matches.add(candidate);
        }
        return List.copyOf(matches);
    }

    public List<VirtualFile> resolveVirtual(String roleName) {
        return resolveVirtual(roleName, null);
    }

    public List<VirtualFile> resolveVirtual(String roleName, Path contextFile) {
        return resolve(roleName, contextFile).stream()
                .map(path -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public VirtualFile findRoleDirectory(Path file) {
        if (file == null) return null;
        Path target = normalizePath(file);
        for (Path root : getRoleRoots(target)) {
            if (!target.startsWith(root)) continue;
            Path relative = root.relativize(target);
            if (relative.getNameCount() == 0) continue;
            Path role = root.resolve(relative.getName(0));
            if (!Files.isDirectory(role)) continue;
            VirtualFile virtualRole = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(role);
            if (virtualRole != null) return virtualRole;
        }
        if (projectRoot != null) {
            for (Path collectionRoot : collectionRoots(projectRoot)) {
                if (!target.startsWith(collectionRoot)) continue;
                Path relative = collectionRoot.relativize(target);
                if (relative.getNameCount() < 5 || !relative.getName(2).toString().equals("roles")) continue;
                Path role = collectionRoot.resolve(relative.getName(0)).resolve(relative.getName(1))
                        .resolve("roles").resolve(relative.getName(3));
                if (!target.startsWith(role) || !Files.isDirectory(role)) continue;
                VirtualFile virtualRole = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(role);
                if (virtualRole != null) return virtualRole;
            }
        }
        return null;
    }

    private static List<Path> collectionRoots(Path projectRoot) {
        return List.of(projectRoot.resolve("ansible_collections"),
                projectRoot.resolve("collections/ansible_collections"));
    }

    private static Path findConfig(Path projectRoot) {
        if (projectRoot == null) return null;
        for (Path current = projectRoot; current != null; current = current.getParent()) {
            Path candidate = current.resolve("ansible.cfg");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static void addConfiguredRoleRoots(Set<Path> roots, Path configFile) {
        String rolesPath = readRolesPath(configFile);
        if (rolesPath == null) return;
        Path configDirectory = configFile.getParent();
        for (String configuredRoot : rolesPath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            String value = configuredRoot.trim();
            if (value.isEmpty()) continue;
            Path root = Path.of(value);
            roots.add(normalizePath(root.isAbsolute() ? root : configDirectory.resolve(root)));
        }
    }

    private static Path normalizePath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException ignored) {
            return absolute;
        }
    }

    private static String readRolesPath(Path config) {
        try {
            boolean defaults = false;
            for (String line : Files.readAllLines(config)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    defaults = trimmed.substring(1, trimmed.length() - 1).trim().equalsIgnoreCase("defaults");
                } else if (defaults && !trimmed.startsWith("#") && !trimmed.startsWith(";") && trimmed.startsWith("roles_path")) {
                    int equals = trimmed.indexOf('=');
                    if (equals >= 0) return trimmed.substring(equals + 1).trim();
                }
            }
        } catch (IOException ignored) {
            // A missing or unreadable config leaves the conventional project roles/ root available.
        }
        return null;
    }

}
