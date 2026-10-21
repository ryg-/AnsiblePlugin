package ir.msdehghan.plugins.ansible.runtime;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import ir.msdehghan.plugins.ansible.model.ansible.task.AnsibleModuleDto;
import ir.msdehghan.plugins.ansible.model.ansible.task.AnsibleModuleParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
public final class AnsibleProjectService implements Disposable {
    private static final String EXECUTABLE_PROPERTY_PREFIX = "ansible.documentation.executable.";
    private static final List<String> MODULE_LIST_ARGUMENTS = List.of("-t", "module", "-l", "--json");
    private static final List<String> CONNECTION_LIST_ARGUMENTS = List.of("-t", "connection", "-l", "--json");
    private static final List<String> FALLBACK_CONNECTION_PLUGINS = List.of(
            "local", "ssh", "paramiko_ssh", "psrp", "winrm", "network_cli", "netconf", "httpapi");

    private final Project project;
    private AnsibleCommandExecutor executor;
    private final CopyOnWriteArrayList<Consumer<CatalogSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private volatile String executable = "ansible-doc";
    private volatile CatalogSnapshot snapshot = new CatalogSnapshot(CatalogStatus.UNAVAILABLE, null,
            "Module catalog has not been loaded.", null);
    private volatile String lastGoodContext;
    private volatile CompletableFuture<CatalogSnapshot> refreshFuture;
    private volatile CompletableFuture<AnsibleCommandResult> commandFuture;
    private final Map<String, CompletableFuture<AnsibleModuleDto>> detailRequests = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<AnsibleCommandResult>> detailCommands = new ConcurrentHashMap<>();
    private volatile Map<String, String> moduleListing = Map.of();
    private volatile Map<String, String> connectionPluginListing = Map.of();
    private volatile CompletableFuture<Map<String, String>> connectionPluginRefreshFuture;
    private volatile boolean disposed;
    private boolean initialLoadStarted;
    private long refreshGeneration;

    public AnsibleProjectService(@NotNull Project project) {
        this.project = project;
        this.executor = new ProcessAnsibleCommandExecutor();
        String configured = userExecutableSetting(project);
        if (configured != null && !configured.isBlank()) executable = configured;
    }

    void setExecutorForTest(@NotNull AnsibleCommandExecutor executor) {
        this.executor = executor;
    }

    public static AnsibleProjectService getInstance(@NotNull Project project) {
        AnsibleProjectService service = project.getService(AnsibleProjectService.class);
        service.ensureLoaded();
        return service;
    }

    public void ensureLoaded() {
        synchronized (this) {
            if (initialLoadStarted || disposed) {
                return;
            }
            initialLoadStarted = true;
        }
        refreshCatalog();
        refreshConnectionPluginCatalog();
    }

    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        String normalized = executable == null || executable.isBlank() ? "ansible-doc" : executable.trim();
        synchronized (this) {
            if (Objects.equals(this.executable, normalized)) {
                return;
            }
            this.executable = normalized;
            if (ApplicationManager.getApplication() != null) {
                PropertiesComponent.getInstance().setValue(executablePropertyKey(project), normalized);
            }
            refreshGeneration++;
            moduleListing = Map.of();
            connectionPluginListing = Map.of();
            if (connectionPluginRefreshFuture != null) connectionPluginRefreshFuture.cancel(true);
            connectionPluginRefreshFuture = null;
            detailCommands.values().forEach(request -> request.cancel(true));
            detailRequests.values().forEach(request -> request.cancel(true));
            detailCommands.clear();
            detailRequests.clear();
            if (commandFuture != null) {
                commandFuture.cancel(true);
            }
            if (refreshFuture != null) {
                refreshFuture.cancel(true);
            }
            commandFuture = null;
            refreshFuture = null;
        }
        refreshCatalog();
        refreshConnectionPluginCatalog();
    }

    public CatalogSnapshot getSnapshot() {
        return snapshot;
    }

    public Map<String, String> getModuleListing() {
        ensureLoaded();
        if (moduleListing.isEmpty() && snapshot.rawListing() != null && snapshot.status() == CatalogStatus.READY) {
            try {
                moduleListing = AnsibleModuleParser.parseListing(snapshot.rawListing());
            } catch (java.io.IOException ignored) {
                moduleListing = Map.of();
            }
        }
        return moduleListing;
    }

    public String resolveModuleName(String requestedName) {
        Map<String, String> modules = getModuleListing();
        if (modules.containsKey(requestedName)) return requestedName;
        String match = null;
        for (String fqcn : modules.keySet()) {
            int separator = fqcn.lastIndexOf('.');
            if (separator >= 0 && fqcn.substring(separator + 1).equals(requestedName)) {
                if (match != null) return null;
                match = fqcn;
            }
        }
        return match;
    }

    public List<String> getConnectionPluginNames() {
        ensureLoaded();
        Map<String, String> plugins = connectionPluginListing;
        if (plugins.isEmpty()) return FALLBACK_CONNECTION_PLUGINS;
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        plugins.keySet().forEach(name -> {
            int separator = name.lastIndexOf('.');
            String shortName = separator < 0 ? name : name.substring(separator + 1);
            if (shortName.isBlank() || shortName.startsWith("_")) return;
            names.add(shortName);
            if (separator >= 0) names.add(name);
        });
        return List.copyOf(names);
    }

    public CompletableFuture<Map<String, String>> refreshConnectionPluginCatalog() {
        final String selectedExecutable;
        synchronized (this) {
            if (disposed) return CompletableFuture.completedFuture(connectionPluginListing);
            if (connectionPluginRefreshFuture != null && !connectionPluginRefreshFuture.isDone()) {
                return connectionPluginRefreshFuture;
            }
            if (!connectionPluginListing.isEmpty()) {
                connectionPluginListing = Map.of();
            }
            selectedExecutable = executable;
            CompletableFuture<Map<String, String>> result = new CompletableFuture<>();
            connectionPluginRefreshFuture = result;
            try {
                executor.execute(selectedExecutable, CONNECTION_LIST_ARGUMENTS, project.getBasePath())
                        .whenComplete((commandResult, error) -> {
                            Map<String, String> parsed = Map.of();
                            Throwable failure = error;
                            if (failure == null && commandResult != null && commandResult.exitCode() == 0) {
                                try {
                                    parsed = AnsibleModuleParser.parseListing(commandResult.output());
                                } catch (java.io.IOException parseError) {
                                    failure = parseError;
                                }
                            } else if (failure == null) {
                                failure = new IllegalStateException("ansible-doc connection listing exited with code "
                                        + (commandResult == null ? "unknown" : commandResult.exitCode()));
                            }
                            synchronized (AnsibleProjectService.this) {
                                if (!disposed && Objects.equals(executable, selectedExecutable)) {
                                    if (failure == null) connectionPluginListing = parsed;
                                    connectionPluginRefreshFuture = null;
                                }
                            }
                            if (failure == null) result.complete(parsed);
                            else result.completeExceptionally(failure);
                        });
            } catch (Throwable error) {
                connectionPluginRefreshFuture = null;
                result.completeExceptionally(error);
            }
            return result;
        }
    }

    public CompletableFuture<AnsibleModuleDto> loadModuleDetails(String moduleName) {
        Map<String, String> modules = getModuleListing();
        String fqcn = resolveModuleName(moduleName);
        if (fqcn == null && modules.isEmpty()) {
            // ansible-doc accepts short names too; use it while the catalog is still loading
            // or unavailable, then use its JSON key as the canonical module name.
            fqcn = moduleName;
        }
        if (fqcn == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown or ambiguous Ansible module: " + moduleName));
        String detailKey = fqcn;
        CompletableFuture<AnsibleModuleDto> detail;
        synchronized (this) {
            if (disposed) return CompletableFuture.failedFuture(new IllegalStateException("Project service is disposed"));
            detail = detailRequests.computeIfAbsent(detailKey, name -> {
                CompletableFuture<AnsibleCommandResult> command = executor.execute(executable,
                        List.of("-t", "module", "-j", name), project.getBasePath());
                detailCommands.put(name, command);
                command.whenComplete((value, error) -> detailCommands.remove(name, command));
                return command.thenApply(result -> {
                        if (result.exitCode() != 0) throw new IllegalStateException("ansible-doc exited with code " + result.exitCode());
                        try {
                            return AnsibleModuleParser.parseDetail(name, result.output());
                        } catch (java.io.IOException error) {
                            throw new java.util.concurrent.CompletionException(error);
                        }
                    });
            });
        }
        detail.whenComplete((value, error) -> {
            if (error != null) {
                detailRequests.remove(detailKey, detail);
            }
        });
        return detail;
    }

    public CompletableFuture<CatalogSnapshot> getRefreshFuture() {
        CompletableFuture<CatalogSnapshot> current = refreshFuture;
        return current == null ? CompletableFuture.completedFuture(snapshot) : current;
    }

    public CompletableFuture<CatalogSnapshot> refreshCatalog() {
        final String context;
        final long generation;
        final CompletableFuture<CatalogSnapshot> result = new CompletableFuture<>();
        synchronized (this) {
            if (disposed) {
                return CompletableFuture.completedFuture(snapshot);
            }
            initialLoadStarted = true;
            if (refreshFuture != null && !refreshFuture.isDone()) {
                return refreshFuture;
            }
            context = contextKey();
            generation = ++refreshGeneration;
            boolean sameContext = context.equals(lastGoodContext);
            if (!sameContext) {
                moduleListing = Map.of();
                detailCommands.values().forEach(request -> request.cancel(true));
                detailRequests.values().forEach(request -> request.cancel(true));
                detailCommands.clear();
                detailRequests.clear();
            }
            String retainedListing = sameContext ? snapshot.rawListing() : null;
            snapshot = new CatalogSnapshot(CatalogStatus.LOADING, retainedListing, "Loading module catalog…", null);
            refreshFuture = result;
        }
        fireSnapshotChanged();

        CompletableFuture<AnsibleCommandResult> command;
        try {
            command = executor.execute(executable, MODULE_LIST_ARGUMENTS, project.getBasePath());
        } catch (Throwable error) {
            command = CompletableFuture.failedFuture(error);
        }
        commandFuture = command;
        command.whenComplete((commandResult, error) -> {
            CatalogSnapshot completed;
            synchronized (this) {
                if (disposed || generation != refreshGeneration) {
                    result.complete(snapshot);
                    return;
                }
                boolean sameContext = context.equals(contextKey());
                Map<String, String> parsedModules = null;
                String parseError = null;
                if (error == null && commandResult != null && commandResult.exitCode() == 0) {
                    try {
                        parsedModules = AnsibleModuleParser.parseListing(commandResult.output());
                    } catch (java.io.IOException malformedJson) {
                        parseError = malformedJson.getMessage();
                    }
                }
                if (sameContext && error == null && commandResult != null && commandResult.exitCode() == 0 && parsedModules != null) {
                    lastGoodContext = context;
                    moduleListing = parsedModules;
                    detailCommands.values().forEach(request -> request.cancel(true));
                    detailRequests.values().forEach(request -> request.cancel(true));
                    detailCommands.clear();
                    detailRequests.clear();
                    completed = new CatalogSnapshot(CatalogStatus.READY, commandResult.output(), null, Instant.now());
                } else {
                    String message = parseError != null ? "Invalid ansible-doc JSON: " + parseError : error == null
                            ? "ansible-doc exited with code " + (commandResult == null ? "unknown" : commandResult.exitCode())
                            : rootMessage(error);
                    boolean canRetain = sameContext && context.equals(lastGoodContext) && snapshot.rawListing() != null;
                    completed = new CatalogSnapshot(canRetain ? CatalogStatus.FAILED_WITH_LAST_GOOD : CatalogStatus.UNAVAILABLE,
                            canRetain ? snapshot.rawListing() : null, message, canRetain ? snapshot.loadedAt() : null);
                }
                snapshot = completed;
                refreshFuture = null;
                commandFuture = null;
            }
            fireSnapshotChanged();
            result.complete(completed);
        });
        return result;
    }

    public void addListener(Consumer<CatalogSnapshot> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<CatalogSnapshot> listener) {
        listeners.remove(listener);
    }

    @Override
    public void dispose() {
        synchronized (this) {
            disposed = true;
            refreshGeneration++;
            detailCommands.values().forEach(request -> request.cancel(true));
            detailRequests.values().forEach(request -> request.cancel(true));
            detailCommands.clear();
            detailRequests.clear();
            CompletableFuture<AnsibleCommandResult> command = commandFuture;
            if (command != null) command.cancel(true);
            CompletableFuture<CatalogSnapshot> refresh = refreshFuture;
            if (refresh != null) refresh.cancel(true);
            CompletableFuture<Map<String, String>> connectionRefresh = connectionPluginRefreshFuture;
            if (connectionRefresh != null) connectionRefresh.cancel(true);
        }
        listeners.clear();
    }

    private String contextKey() {
        String projectPath = project.getBasePath();
        return (projectPath == null ? "" : new File(projectPath).getAbsolutePath()) + "\u0000"
                + executable + "\u0000" + new java.util.TreeMap<>(System.getenv()) + "\u0000" + configIdentity(projectPath);
    }

    private static String userExecutableSetting(Project project) {
        return ApplicationManager.getApplication() == null ? null
                : PropertiesComponent.getInstance().getValue(executablePropertyKey(project));
    }

    private static String executablePropertyKey(Project project) {
        String identity = project.getBasePath() == null ? project.getName() : project.getBasePath();
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return EXECUTABLE_PROPERTY_PREFIX + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String configIdentity(String projectPath) {
        String selected = System.getenv("ANSIBLE_CONFIG");
        File config = selected == null || selected.isBlank() ? null : new File(selected);
        if (config != null && !config.isAbsolute() && projectPath != null) config = new File(projectPath, selected);
        if (config == null || !config.isFile()) {
            File directory = projectPath == null ? null : new File(projectPath).getAbsoluteFile();
            while (directory != null) {
                File candidate = new File(directory, "ansible.cfg");
                if (candidate.isFile()) { config = candidate; break; }
                directory = directory.getParentFile();
            }
        }
        if (config == null || !config.isFile()) {
            File userConfig = new File(System.getProperty("user.home", ""), ".ansible.cfg");
            if (userConfig.isFile()) config = userConfig;
        }
        if (config == null || !config.isFile()) return "";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(java.nio.file.Files.readAllBytes(config.toPath()));
            return config.getAbsolutePath() + ":" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException ignored) {
            return config.getAbsolutePath() + ":" + config.lastModified() + ":" + config.length();
        }
    }

    private void fireSnapshotChanged() {
        if (listeners.isEmpty()) {
            return;
        }
        CatalogSnapshot current = snapshot;
        var application = ApplicationManager.getApplication();
        if (application == null) {
            return;
        }
        application.invokeLater(() -> {
            if (!disposed) {
                for (Consumer<CatalogSnapshot> listener : listeners) {
                    listener.accept(current);
                }
            }
        });
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    public record CatalogSnapshot(CatalogStatus status, String rawListing, String message, Instant loadedAt) {
    }
}
