package ir.msdehghan.plugins.ansible.runtime;

import com.intellij.openapi.project.Project;
import ir.msdehghan.plugins.ansible.model.ansible.task.AnsibleModuleDto;
import junit.framework.TestCase;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

public class AnsibleProjectServiceTest extends TestCase {
    public void testProcessExecutorSetsWritableAnsibleLocalTempByDefault() throws Exception {
        String configuredTemp = System.getenv("ANSIBLE_LOCAL_TEMP");
        String expectedTemp = configuredTemp == null || configuredTemp.isBlank()
                ? System.getProperty("java.io.tmpdir") : configuredTemp;
        var environment = new HashMap<>(System.getenv());

        ProcessAnsibleCommandExecutor.configureEnvironment(environment);

        assertEquals(expectedTemp, environment.get("ANSIBLE_LOCAL_TEMP"));
    }

    public void testRefreshPublishesLoadingAndReadyStates() throws Exception {
        FakeExecutor executor = new FakeExecutor();
        CompletableFuture<AnsibleCommandResult> response = new CompletableFuture<>();
        executor.responses.add(response);

        AnsibleProjectService service = serviceFor("/tmp/project-one", executor);
        try {
            CompletableFuture<AnsibleProjectService.CatalogSnapshot> refresh = service.refreshCatalog();
            assertEquals(CatalogStatus.LOADING, service.getSnapshot().status());
            assertEquals("ansible-doc", executor.executable);
            assertEquals(List.of("-t", "module", "-l", "--json"), executor.arguments);
            assertEquals("/tmp/project-one", executor.workingDirectory);

            response.complete(new AnsibleCommandResult(0, "{\"ansible.builtin.copy\": {}}"));
            refresh.get();

            assertEquals(CatalogStatus.READY, service.getSnapshot().status());
            assertEquals("{\"ansible.builtin.copy\": {}}", service.getSnapshot().rawListing());
            assertNull(service.getSnapshot().message());
        } finally {
            service.dispose();
        }
    }

    public void testInitialFailureHasVisibleReasonAndNoCatalog() throws Exception {
        FakeExecutor executor = new FakeExecutor();
        executor.responses.add(CompletableFuture.failedFuture(new IOException("Executable not found")));

        AnsibleProjectService service = serviceFor("/tmp/project-one", executor);
        try {
            service.refreshCatalog().get();

            assertEquals(CatalogStatus.UNAVAILABLE, service.getSnapshot().status());
            assertTrue(service.getSnapshot().message().contains("Executable not found"));
            assertNull(service.getSnapshot().rawListing());
        } finally {
            service.dispose();
        }
    }

    public void testFailedRefreshRetainsSameContextCatalog() throws Exception {
        FakeExecutor executor = new FakeExecutor();
        executor.responses.add(CompletableFuture.completedFuture(
                new AnsibleCommandResult(0, "{\"ansible.builtin.copy\": {}}")));
        executor.responses.add(CompletableFuture.failedFuture(new IOException("Temporary failure")));

        AnsibleProjectService service = serviceFor("/tmp/project-one", executor);
        try {
            service.refreshCatalog().get();
            service.refreshCatalog().get();

            assertEquals(CatalogStatus.FAILED_WITH_LAST_GOOD, service.getSnapshot().status());
            assertTrue(service.getSnapshot().message().contains("Temporary failure"));
            assertEquals("{\"ansible.builtin.copy\": {}}", service.getSnapshot().rawListing());
        } finally {
            service.dispose();
        }
    }

    public void testChangedExecutableDoesNotReusePreviousCatalog() throws Exception {
        FakeExecutor executor = new FakeExecutor();
        executor.responses.add(CompletableFuture.completedFuture(
                new AnsibleCommandResult(0, "{\"ansible.builtin.copy\": {}}")));
        executor.responses.add(CompletableFuture.failedFuture(new IOException("Other environment unavailable")));

        AnsibleProjectService service = serviceFor("/tmp/project-one", executor);
        try {
            service.refreshCatalog().get();
            service.setExecutable("/opt/other/bin/ansible-doc");
            service.getRefreshFuture().get();

            assertEquals(CatalogStatus.UNAVAILABLE, service.getSnapshot().status());
            assertNull(service.getSnapshot().rawListing());
            assertTrue("A changed Ansible context must not expose the previous module names",
                    service.getModuleListing().isEmpty());
            assertEquals("/opt/other/bin/ansible-doc", service.getExecutable());
        } finally {
            service.dispose();
        }
    }

    public void testDetailCommandsAreCancelledWhenExecutableChangesAndOnDispose() throws Exception {
        String executableChangeProject = "/tmp/ansible-project-executable-change-" + java.util.UUID.randomUUID();
        FakeExecutor executor = new FakeExecutor();
        executor.responses.add(CompletableFuture.completedFuture(new AnsibleCommandResult(0,
                "{\"vendor.storage.snapshot\":\"Snapshot\"}")));
        CompletableFuture<AnsibleCommandResult> pending = new CompletableFuture<>();
        executor.responses.add(pending);
        AnsibleProjectService service = serviceFor(executableChangeProject, executor);
        service.refreshCatalog().get();
        service.loadModuleDetails("snapshot");

        service.setExecutable("/opt/other/bin/ansible-doc");

        assertTrue("Context changes must cancel the original process future", pending.isCancelled());
        service.dispose();

        FakeExecutor refreshExecutor = new FakeExecutor();
        refreshExecutor.responses.add(CompletableFuture.completedFuture(new AnsibleCommandResult(0,
                "{\"vendor.storage.snapshot\":\"Snapshot\"}")));
        CompletableFuture<AnsibleCommandResult> pendingAtRefresh = new CompletableFuture<>();
        refreshExecutor.responses.add(pendingAtRefresh);
        refreshExecutor.responses.add(CompletableFuture.completedFuture(new AnsibleCommandResult(0,
                "{\"vendor.storage.snapshot\":\"Updated snapshot\"}")));
        AnsibleProjectService refreshedService = serviceFor("/tmp/project-refresh", refreshExecutor);
        refreshedService.refreshCatalog().get();
        refreshedService.loadModuleDetails("snapshot");
        refreshedService.refreshCatalog().get();
        assertTrue("A successful catalog refresh must cancel stale detail requests", pendingAtRefresh.isCancelled());
        refreshedService.dispose();

        FakeExecutor disposeExecutor = new FakeExecutor();
        disposeExecutor.responses.add(CompletableFuture.completedFuture(new AnsibleCommandResult(0,
                "{\"vendor.storage.snapshot\":\"Snapshot\"}")));
        CompletableFuture<AnsibleCommandResult> pendingAtDispose = new CompletableFuture<>();
        disposeExecutor.responses.add(pendingAtDispose);
        AnsibleProjectService disposedService = serviceFor("/tmp/project-two", disposeExecutor);
        disposedService.refreshCatalog().get();
        disposedService.loadModuleDetails("snapshot");
        disposedService.dispose();
        assertTrue("Disposal must cancel detail processes", pendingAtDispose.isCancelled());
    }

    public void testFailedDetailRequestCanBeRetried() throws Exception {
        FakeExecutor executor = new FakeExecutor();
        executor.responses.add(CompletableFuture.completedFuture(new AnsibleCommandResult(0,
                "{\"vendor.storage.snapshot\":\"Snapshot\"}")));
        executor.responses.add(CompletableFuture.failedFuture(new IOException("temporary detail failure")));
        executor.responses.add(CompletableFuture.completedFuture(new AnsibleCommandResult(0,
                "{\"vendor.storage.snapshot\":{\"doc\":{\"module\":\"vendor.storage.snapshot\",\"options\":{}}}}")));
        AnsibleProjectService service = serviceFor("/tmp/project-one", executor);
        try {
            service.refreshCatalog().get();
            try {
                service.loadModuleDetails("snapshot").get();
                fail("First detail request should fail");
            } catch (java.util.concurrent.ExecutionException expected) { }
            assertNotNull(service.loadModuleDetails("snapshot").get());
        } finally {
            service.dispose();
        }
    }

    public void testConfigChangeClearsLastGoodCatalogAfterFailedRefresh() throws Exception {
        Path projectDirectory = Files.createTempDirectory("ansible-project-context");
        FakeExecutor executor = new FakeExecutor();
        executor.responses.add(CompletableFuture.completedFuture(new AnsibleCommandResult(0,
                "{\"ansible.builtin.copy\":{}}")));
        executor.responses.add(CompletableFuture.failedFuture(new IOException("refresh failed")));
        AnsibleProjectService service = serviceFor(projectDirectory.toString(), executor);
        try {
            service.refreshCatalog().get();
            Files.writeString(projectDirectory.resolve("ansible.cfg"), "[defaults]\ncollections_path = ./collections\n");
            service.refreshCatalog().get();

            assertEquals(CatalogStatus.UNAVAILABLE, service.getSnapshot().status());
            assertNull(service.getSnapshot().rawListing());
            assertTrue("A changed Ansible config must not expose modules from the previous context",
                    service.getModuleListing().isEmpty());
        } finally {
            service.dispose();
            Files.deleteIfExists(projectDirectory.resolve("ansible.cfg"));
            Files.deleteIfExists(projectDirectory);
        }
    }

    public void testModuleLookupUsesFqcnAndRejectsAmbiguousShortNames() throws Exception {
        FakeExecutor executor = new FakeExecutor();
        executor.responses.add(CompletableFuture.completedFuture(new AnsibleCommandResult(0, """
                {"ansible.builtin.copy":"Copy files","vendor.storage.copy":"Copy storage","vendor.storage.snapshot":"Snapshot"}
                """)));
        executor.responses.add(CompletableFuture.completedFuture(new AnsibleCommandResult(0, """
                {"vendor.storage.snapshot":{"doc":{"module":"vendor.storage.snapshot","options":{}}}}
                """)));
        AnsibleProjectService service = serviceFor("/tmp/project-one", executor);
        try {
            service.refreshCatalog().get();

            assertNull(service.resolveModuleName("copy"));
            assertEquals("vendor.storage.snapshot", service.resolveModuleName("snapshot"));
            assertEquals("ansible.builtin.copy", service.resolveModuleName("ansible.builtin.copy"));
            assertNotNull(service.loadModuleDetails("snapshot").get());
            assertEquals(List.of("-t", "module", "-j", "vendor.storage.snapshot"), executor.arguments);
        } finally {
            service.dispose();
        }
    }

    public void testModuleDetailsCanLoadBeforeCatalogFinishes() throws Exception {
        FakeExecutor executor = new FakeExecutor();
        CompletableFuture<AnsibleCommandResult> listing = new CompletableFuture<>();
        executor.responses.add(listing);
        executor.responses.add(CompletableFuture.completedFuture(new AnsibleCommandResult(0, """
                {"ansible.builtin.file":{"doc":{"module":"file","short_description":"Manage files","options":{"path":{"type":"path","required":true}}}}}
                """)));
        AnsibleProjectService service = serviceFor("/tmp/project-one", executor);
        try {
            service.refreshCatalog();

            assertNotNull(service.loadModuleDetails("file").get());

            assertEquals(List.of("-t", "module", "-j", "file"), executor.arguments);
            assertEquals(CatalogStatus.LOADING, service.getSnapshot().status());
        } finally {
            service.dispose();
        }
    }

    public void testMalformedSuccessfulListingIsReportedInsteadOfPublishingEmptyCatalog() throws Exception {
        FakeExecutor executor = new FakeExecutor();
        executor.responses.add(CompletableFuture.completedFuture(new AnsibleCommandResult(0, "not-json")));
        AnsibleProjectService service = serviceFor("/tmp/project-one", executor);
        try {
            service.refreshCatalog().get();

            assertEquals(CatalogStatus.UNAVAILABLE, service.getSnapshot().status());
            assertTrue(service.getSnapshot().message().contains("Invalid ansible-doc JSON"));
            assertTrue(service.getModuleListing().isEmpty());
        } finally {
            service.dispose();
        }
    }

    public void testCatalogsAreIsolatedPerProjectService() throws Exception {
        FakeExecutor firstExecutor = new FakeExecutor();
        firstExecutor.responses.add(CompletableFuture.completedFuture(
                new AnsibleCommandResult(0, "{\"ansible.builtin.copy\": {}}")));
        FakeExecutor secondExecutor = new FakeExecutor();
        secondExecutor.responses.add(CompletableFuture.completedFuture(
                new AnsibleCommandResult(0, "{\"example.collection.module\": {}}")));
        AnsibleProjectService first = serviceFor("/tmp/project-one", firstExecutor);
        AnsibleProjectService second = serviceFor("/tmp/project-two", secondExecutor);
        try {
            first.refreshCatalog().get();
            second.refreshCatalog().get();

            assertEquals("{\"ansible.builtin.copy\": {}}", first.getSnapshot().rawListing());
            assertEquals("{\"example.collection.module\": {}}", second.getSnapshot().rawListing());
        } finally {
            first.dispose();
            second.dispose();
        }
    }

    public void testConnectionPluginCatalogUsesInstalledNamesAndShortAliases() throws Exception {
        FakeExecutor executor = new FakeExecutor();
        executor.responses.add(CompletableFuture.completedFuture(new AnsibleCommandResult(0, "{}")));
        executor.responses.add(CompletableFuture.completedFuture(new AnsibleCommandResult(0, """
                {"ansible.builtin.local":"execute on controller",
                 "ansible.builtin._hidden":"internal alias",
                 "community.docker.docker":"Run tasks in containers"}
                """)));
        AnsibleProjectService service = serviceFor("/tmp/project-one", executor);
        try {
            service.ensureLoaded();
            service.getRefreshFuture().get();
            List<String> names = service.getConnectionPluginNames();

            assertTrue(names.contains("local"));
            assertTrue(names.contains("ansible.builtin.local"));
            assertTrue(names.contains("docker"));
            assertTrue(names.contains("community.docker.docker"));
            assertFalse(names.contains("_hidden"));
        } finally {
            service.dispose();
        }
    }


    public void testProcessExecutorInheritsEnvironmentAndUsesWorkingDirectory() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Path fakeCli = createFakeAnsibleDocJar();
        String projectPath = System.getProperty("java.io.tmpdir");
        List<String> arguments = List.of("-jar", fakeCli.toString(), "-t", "module", "-l", "--json");

        AnsibleCommandResult result = new ProcessAnsibleCommandExecutor()
                .execute(executable, arguments, projectPath).get();

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("cwd=" + Path.of(projectPath).toAbsolutePath()));
        assertTrue(result.output().contains("path=" + System.getenv("PATH")));
        assertTrue(result.output().contains("args=-t module -l --json"));
        Files.deleteIfExists(fakeCli);
    }

    public void testProcessExecutorKeepsStderrOutOfJsonStdout() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Path fakeCli = createFakeAnsibleDocJar();

        AnsibleCommandResult result = new ProcessAnsibleCommandExecutor()
                .execute(executable, List.of("-jar", fakeCli.toString(), "diagnostic"), null).get();

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("args=diagnostic"));
        assertFalse(result.output().contains("non-fatal diagnostic"));
        assertEquals("non-fatal diagnostic" + System.lineSeparator(), result.errorOutput());
        Files.deleteIfExists(fakeCli);
    }

    public void testProcessExecutorFutureCanBeCancelled() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Path fakeCli = createFakeAnsibleDocJar();
        CompletableFuture<AnsibleCommandResult> process = new ProcessAnsibleCommandExecutor().execute(executable,
                List.of("-jar", fakeCli.toString(), "wait"),
                System.getProperty("java.io.tmpdir"));

        assertTrue(process.cancel(true));
        assertTrue(process.isCancelled());
        Files.deleteIfExists(fakeCli);
    }

    public void testProcessExecutorTimesOutACommand() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Path fakeCli = createFakeAnsibleDocJar();
        CompletableFuture<AnsibleCommandResult> process = new ProcessAnsibleCommandExecutor(1).execute(executable,
                List.of("-jar", fakeCli.toString(), "wait"), System.getProperty("java.io.tmpdir"));

        try {
            process.get(5, java.util.concurrent.TimeUnit.SECONDS);
            fail("Expected the command deadline to fail the future");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause().getMessage().contains("timed out"));
        } finally {
            Files.deleteIfExists(fakeCli);
        }
    }

    private static Path createFakeAnsibleDocJar() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeAnsibleDoc.class.getName());
        Path jar = Files.createTempFile("fake-ansible-doc", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest);
             InputStream classBytes = FakeAnsibleDoc.class.getResourceAsStream("FakeAnsibleDoc.class")) {
            if (classBytes == null) {
                throw new IOException("Fake ansible-doc class bytes are unavailable");
            }
            output.putNextEntry(new JarEntry("ir/msdehghan/plugins/ansible/runtime/FakeAnsibleDoc.class"));
            classBytes.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }

    private static AnsibleProjectService serviceFor(String basePath, FakeExecutor executor) {
        Project project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBasePath" -> basePath;
                    case "getName" -> basePath;
                    case "isDisposed" -> false;
                    case "toString" -> "TestProject(" + basePath + ")";
                    default -> null;
                });
        AnsibleProjectService service = new AnsibleProjectService(project);
        service.setExecutorForTest(executor);
        return service;
    }

    private static final class FakeExecutor implements AnsibleCommandExecutor {
        private final Queue<CompletableFuture<AnsibleCommandResult>> responses = new ArrayDeque<>();
        private String executable;
        private List<String> arguments;
        private String workingDirectory;

        @Override
        public CompletableFuture<AnsibleCommandResult> execute(String executable, List<String> arguments,
                                                               String workingDirectory) {
            this.executable = executable;
            this.arguments = List.copyOf(arguments);
            this.workingDirectory = workingDirectory;
            return responses.remove();
        }
    }
}
