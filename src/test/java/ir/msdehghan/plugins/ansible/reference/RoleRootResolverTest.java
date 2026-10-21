package ir.msdehghan.plugins.ansible.reference;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class RoleRootResolverTest extends TestCase {
    private Path temporaryRoot;

    @Override
    protected void setUp() throws Exception {
        temporaryRoot = Files.createTempDirectory("ansible-role-roots");
    }

    @Override
    protected void tearDown() throws Exception {
        try (var paths = Files.walk(temporaryRoot)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
        }
    }

    public void testUsesProjectRolesAndMultipleConfigRootsRelativeToConfig() throws Exception {
        Path project = Files.createDirectories(temporaryRoot.resolve("project"));
        Path configDirectory = Files.createDirectories(project.resolve("config"));
        Path external = Files.createDirectories(temporaryRoot.resolve("shared-roles"));
        Path localRole = Files.createDirectories(project.resolve("roles/web"));
        Path configRole = Files.createDirectories(configDirectory.resolve("roles/db"));
        Path externalRole = Files.createDirectories(external.resolve("monitoring"));
        Path config = configDirectory.resolve("ansible.cfg");
        Files.writeString(config, "[defaults]\nroles_path = roles:" + external + "\n");

        RoleRootResolver resolver = new RoleRootResolver(project, config);

        assertEquals(3, resolver.getRoleRoots().size());
        assertEquals(localRole.toAbsolutePath().normalize(), resolver.resolve("web").getFirst());
        assertEquals(configRole.toAbsolutePath().normalize(), resolver.resolve("db").getFirst());
        assertEquals(externalRole.toAbsolutePath().normalize(), resolver.resolve("monitoring").getFirst());
    }

    public void testRejectsRoleNamesEscapingConfiguredRoot() throws Exception {
        Path project = Files.createDirectories(temporaryRoot.resolve("project"));
        Path outside = Files.createDirectories(temporaryRoot.resolve("outside"));
        Files.createDirectories(project.resolve("roles"));
        Files.createDirectories(outside.resolve("secret"));

        RoleRootResolver resolver = new RoleRootResolver(project, null);

        assertTrue(resolver.resolve("../outside/secret").isEmpty());
    }

    public void testResolvesProjectLocalCollectionRoleByFqcn() throws Exception {
        Path project = Files.createDirectories(temporaryRoot.resolve("project"));
        Path collectionRole = Files.createDirectories(project.resolve(
                "ansible_collections/acme/web/roles/proxy"));
        Path outsideRole = Files.createDirectories(temporaryRoot.resolve(
                "ansible_collections/acme/web/roles/proxy"));

        RoleRootResolver resolver = new RoleRootResolver(project, null);

        assertEquals(List.of(collectionRole.toAbsolutePath().normalize()),
                resolver.resolve("acme.web.proxy"));
        assertFalse(resolver.resolve("acme.web.proxy").contains(outsideRole.toAbsolutePath().normalize()));
    }

    public void testResolvesPlaybookAdjacentRolesDirectory() throws Exception {
        Path project = Files.createDirectories(temporaryRoot.resolve("project"));
        Path playbookDirectory = Files.createDirectories(project.resolve("ubuntu/playbooks"));
        Path role = Files.createDirectories(playbookDirectory.resolve("roles/disable-kvm"));
        Path playbook = Files.writeString(playbookDirectory.resolve("setup.yml"), "- hosts: all\n");

        RoleRootResolver resolver = new RoleRootResolver(project, null);

        assertEquals(List.of(role.toAbsolutePath().normalize()), resolver.resolve("disable-kvm", playbook));
    }

    public void testResolvesRolesFromConfigAdjacentToPlaybook() throws Exception {
        Path project = Files.createDirectories(temporaryRoot.resolve("project"));
        Path playbookDirectory = Files.createDirectories(project.resolve("teams/jbr"));
        Path role = Files.createDirectories(project.resolve("shared-roles/maintenance"));
        Files.writeString(playbookDirectory.resolve("ansible.cfg"), "[defaults]\nroles_path = ../../shared-roles\n");
        Path playbook = Files.writeString(playbookDirectory.resolve("setup.yml"), "- hosts: all\n");

        RoleRootResolver resolver = new RoleRootResolver(project, null);

        assertEquals(List.of(role.toAbsolutePath().normalize()), resolver.resolve("maintenance", playbook));
    }

    public void testResolvesAdjacentRolesWhenContextUsesCanonicalPathOfSymlinkedProject() throws Exception {
        Path realProject = Files.createDirectories(temporaryRoot.resolve("real-project"));
        Path playbookDirectory = Files.createDirectories(realProject.resolve("ubuntu/playbooks"));
        Path role = Files.createDirectories(playbookDirectory.resolve("roles/netconsole-collector"));
        Path playbook = Files.writeString(playbookDirectory.resolve("site.yml"), "- hosts: all\n");
        Path linkedProject = temporaryRoot.resolve("linked-project");
        Files.createSymbolicLink(linkedProject, realProject);

        RoleRootResolver resolver = new RoleRootResolver(linkedProject, null);

        assertEquals(List.of(role.toRealPath()), resolver.resolve("netconsole-collector", playbook));
    }
}
