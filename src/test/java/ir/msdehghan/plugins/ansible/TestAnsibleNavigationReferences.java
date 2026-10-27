package ir.msdehghan.plugins.ansible;

import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.psi.util.PsiTreeUtil;
import ir.msdehghan.plugins.ansible.AnsibleModels;
import ir.msdehghan.plugins.ansible.AnsibleUtil;
import ir.msdehghan.plugins.ansible.runtime.AnsibleTestProjectSupport;
import ir.msdehghan.plugins.ansible.runtime.AnsibleCommandResult;
import ir.msdehghan.plugins.ansible.model.yml.type.api.ReferenceProvider;
import org.jetbrains.yaml.psi.YAMLScalar;
import org.jetbrains.yaml.psi.YAMLFile;
import org.jetbrains.yaml.psi.YAMLMapping;
import org.jetbrains.yaml.psi.YAMLKeyValue;

import java.nio.file.Files;
import java.nio.file.Path;

public class TestAnsibleNavigationReferences extends BasePlatformTestCase {
    public void testRoleUnderPlaybooksDirectoryIsRecognized() throws Exception {
        PsiFile roleTask = AnsibleTestProjectSupport.addProjectFile(getProject(),
                "ubuntu/playbooks/roles/web/tasks/main.yml", "- debug: msg=hello\n");

        assertTrue("Nested role task file should be recognized as an Ansible role document",
                AnsibleUtil.isRoleDocument(((YAMLFile) roleTask).getDocuments().getFirst()));
    }

    public void testRoleUnderSymlinkedProjectPathIsRecognized() throws Exception {
        String rolePath = "ubuntu/playbooks/roles/symlink_role";
        String relativePath = rolePath + "/tasks/navigation.yaml";
        PsiFile handler = AnsibleTestProjectSupport.addProjectFile(getProject(), rolePath + "/handlers/main.yaml", """
                - name: restart symlink service
                  command: true
                """);
        PsiFile template = AnsibleTestProjectSupport.addProjectFile(getProject(), rolePath + "/templates/service.j2", "[Service]\n");
        AnsibleTestProjectSupport.addProjectFile(getProject(), relativePath, """
                - name: Install service
                  template:
                    src: service.j2
                  notify: restart symlink service
                """);
        Path projectRoot = Path.of(getProject().getBasePath());
        Path aliasRoot = projectRoot.resolveSibling(projectRoot.getFileName() + "-alias");
        Files.createSymbolicLink(aliasRoot, projectRoot);
        try {
            var aliasFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(aliasRoot.resolve(relativePath));
            assertNotNull("File opened through the project symlink", aliasFile);
            PsiFile roleTask = PsiManager.getInstance(getProject()).findFile(aliasFile);
            assertNotNull(roleTask);
            assertEquals("The test must preserve the symlink path", aliasRoot.resolve(relativePath).toString(),
                    roleTask.getVirtualFile().getPath());
            assertTrue("Role task should be recognized through a symlinked project path",
                    AnsibleUtil.isRoleDocument(((YAMLFile) roleTask).getDocuments().getFirst()));

            configureAt(roleTask, "restart symlink service", "restart symlink service".length() - 1);
            assertTrue("notify should resolve through a symlinked project path",
                    myFixture.getReferenceAtCaretPositionWithAssertion().isReferenceTo(findScalar(handler, "restart symlink service")));
            configureAt(roleTask, "service.j2", "service.j2".length() - 1);
            assertTrue("template src should resolve through a symlinked project path",
                    myFixture.getReferenceAtCaretPositionWithAssertion().isReferenceTo(template));
        } finally {
            Files.deleteIfExists(aliasRoot);
        }
    }

    public void testBuiltinFqcnIncludeRoleNameResolvesRoleDirectory() throws Exception {
        PsiFile roleTask = AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/web/tasks/include_role.yml", """
                - name: Include role
                  ansible.builtin.include_role:
                    name: web
                """);
        assertTrue("Role directory should be recognized", AnsibleUtil.isRoleDocument(((YAMLFile) roleTask).getDocuments().getFirst()));
        configureAt(roleTask, "name: web", 6);
        YAMLScalar includeRoleName = findScalar(roleTask, "web");
        var schema = AnsibleModels.MODEL_PROCESSOR.locate(includeRoleName);
        assertNotNull("Include role name schema", schema);
        assertTrue("Include role name must be navigable", schema.getType() instanceof ReferenceProvider);

        PsiReference reference = myFixture.getReferenceAtCaretPositionWithAssertion();
        assertEquals("web", reference.getCanonicalText());
        assertTrue(reference.isReferenceTo(roleTask.getParent().getParent()));
    }

    public void testNotifyResolvesRoleHandlerListenTopic() throws Exception {
        AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/web/handlers/web_notify.yml", """
                - name: Restart web service
                  listen: restart web
                  command: true
                """);
        PsiFile tasks = AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/web/tasks/notify.yml", """
                - name: Update
                  notify: restart web
                """);
        configureAt(tasks, "restart web", 5);

        PsiReference reference = myFixture.getReferenceAtCaretPositionWithAssertion();

        assertTrue(reference instanceof PsiPolyVariantReference);
        assertTrue(((PsiPolyVariantReference) reference).multiResolve(false).length > 0);
        YAMLScalar target = (YAMLScalar) reference.resolve();
        assertEquals("restart web", target.getTextValue());
        assertEquals("web_notify.yml", target.getContainingFile().getName());
        YAMLMapping handler = PsiTreeUtil.getParentOfType(target, YAMLMapping.class);
        YAMLKeyValue name = handler.getKeyValues().stream().filter(pair -> pair.getKeyText().equals("name")).findFirst().orElseThrow();
        assertEquals("Restart web service", ((YAMLScalar) name.getValue()).getTextValue());
    }

    public void testGoToDeclarationOnRoleNotifyNavigatesToHandlerName() throws Exception {
        PsiFile handler = AnsibleTestProjectSupport.addProjectFile(getProject(),
                "ubuntu/playbooks/roles/netconsole-collector/handlers/main.yaml", """
                        ---
                        - name: restart netconsole collector
                          systemd:
                            name: netconsole-collector.service
                            state: restarted
                        """);
        PsiFile tasks = AnsibleTestProjectSupport.addProjectFile(getProject(),
                "ubuntu/playbooks/roles/netconsole-collector/tasks/notify-navigation.yaml", """
                        - name: Install service
                          template:
                            src: netconsole-collector.service.j2
                          notify: restart netconsole collector
                        """);

        configureAt(tasks, "restart netconsole collector", "restart netconsole collector".length() - 1);
        YAMLScalar notifyName = findScalar(tasks, "restart netconsole collector");
        var notifySchema = AnsibleModels.MODEL_PROCESSOR.locate(notifyName);
        assertNotNull("notify value schema; file=" + tasks.getText(), notifySchema);
        assertTrue("notify value must use a reference provider, schema=" + notifySchema,
                notifySchema.getType() instanceof ReferenceProvider);
        assertTrue("notify should resolve to the handler name", myFixture.getReferenceAtCaretPositionWithAssertion()
                .isReferenceTo(findScalar(handler, "restart netconsole collector")));
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION);

        assertEditorNavigatedTo(handler);
    }

    public void testGoToDeclarationOnRoleHandlerNameNavigatesBackToNotifyingTask() throws Exception {
        PsiFile handler = AnsibleTestProjectSupport.addProjectFile(getProject(),
                "ubuntu/playbooks/roles/netconsole-collector-reverse-link/handlers/reverse-navigation.yaml", """
                        ---
                        - name: restart netconsole collector
                          systemd:
                            name: netconsole-collector.service
                            state: restarted
                        """);
        PsiFile tasks = AnsibleTestProjectSupport.addProjectFile(getProject(),
                "ubuntu/playbooks/roles/netconsole-collector-reverse-link/tasks/main.yaml", """
                        - name: Install netconsole collector service
                          template:
                            src: netconsole-collector.service.j2
                            dest: /etc/systemd/system/netconsole-collector.service
                            owner: root
                            group: root
                            mode: '0644'
                          notify: restart netconsole collector
                        """);

        configureAt(handler, "restart netconsole collector", "restart netconsole collector".length() - 1);
        PsiReference reference = myFixture.getReferenceAtCaretPositionWithAssertion();

        assertTrue("handler name should resolve back to the notifying task",
                reference.isReferenceTo(findScalar(tasks, "restart netconsole collector")));
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION);

        assertEditorNavigatedTo(tasks);
    }

    public void testGoToDeclarationOnTemplateSrcNavigatesToRoleTemplate() throws Exception {
        PsiFile template = AnsibleTestProjectSupport.addProjectFile(getProject(),
                "ubuntu/playbooks/roles/netconsole-collector/templates/netconsole-collector.service.j2", "[Service]\n");
        PsiFile tasks = AnsibleTestProjectSupport.addProjectFile(getProject(),
                "ubuntu/playbooks/roles/netconsole-collector/tasks/template-navigation.yaml", """
                        - name: Install service
                          template:
                            src: netconsole-collector.service.j2
                        """);
        var pendingDetails = AnsibleTestProjectSupport.installModuleListingWithoutDetails(getProject(),
                "{\"ansible.builtin.template\":\"Template module\"}");

        configureAt(tasks, "netconsole-collector.service.j2", "netconsole-collector.service.j2".length() - 1);
        assertTrue("template src should resolve to the role template",
                myFixture.getReferenceAtCaretPositionWithAssertion().isReferenceTo(template));
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION);

        assertEditorNavigatedTo(template);
        pendingDetails.complete(new AnsibleCommandResult(0, """
                {"ansible.builtin.template":{"doc":{"module":"ansible.builtin.template","options":{"src":{"type":"path"}}}}}
                """));
    }

    public void testNotifyDoesNotResolveHandlerFromAnotherPlay() {
        PsiFile playbook = myFixture.addFileToProject("playbook.yml", """
                - hosts: web
                  tasks:
                    - name: Update web
                      command: true
                      notify: restart service
                  handlers:
                    - name: restart service
                      command: web-service restart
                - hosts: db
                  tasks:
                    - name: Update db
                      command: true
                      notify: restart service
                  handlers:
                    - name: restart service
                      command: db-service restart
                """);
        configureAt(playbook, "notify: restart service", "notify: restart service".length() - 4);

        PsiReference reference = myFixture.getReferenceAtCaretPositionWithAssertion();

        assertEquals("restart service", ((YAMLScalar) reference.resolve()).getTextValue());
        assertTrue(reference.resolve().getParent().getParent().getText().contains("web-service"));
    }

    public void testRoleTemplatePathResolvesFromTemplatesRoot() throws Exception {
        PsiFile target = AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/web/templates/nested/site.conf.j2", "server {}\n");
        PsiFile tasks = AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/web/tasks/template.yml", """
                - ansible.builtin.template:
                    src: nested/site.conf.j2
                """);
        AnsibleTestProjectSupport.installModuleCatalog(getProject(), """
                {"ansible.builtin.template":"Template module","ansible.builtin.copy":"Copy module"}
                """, java.util.Map.of(
                "ansible.builtin.template", """
                        {"ansible.builtin.template":{"doc":{"module":"ansible.builtin.template","options":{"src":{"type":"path"}}}}}
                        """,
                "ansible.builtin.copy", """
                        {"ansible.builtin.copy":{"doc":{"module":"ansible.builtin.copy","options":{"src":{"type":"path"}}}}}
                        """));
        configureAt(tasks, "nested/site.conf.j2", "nested/site.conf.j2".length() - 1);
        YAMLScalar templatePath = findScalar(tasks, "nested/site.conf.j2");
        var schema = AnsibleModels.MODEL_PROCESSOR.locate(templatePath);
        assertNotNull("Template src schema", schema);
        assertTrue("Template src must be navigable", schema.getType() instanceof ReferenceProvider);
        PsiReference reference = myFixture.getReferenceAtCaretPositionWithAssertion();
        assertTrue(reference.isReferenceTo(target));

        PsiFile copyTarget = AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/web/files/nested/app.conf", "app=true\n");
        PsiFile copyTask = AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/web/tasks/copy.yml", """
                - name: Copy role file
                  copy:
                    src: nested/app.conf
                """);
        configureAt(copyTask, "nested/app.conf", "nested/app.conf".length() - 1);
        warmSchemaAtCaret(copyTask, "nested/app.conf");
        assertTrue(myFixture.getReferenceAtCaretPositionWithAssertion().isReferenceTo(copyTarget));
    }

    public void testTemplatePathResolvesFromNestedPlaybooksRole() throws Exception {
        PsiFile target = AnsibleTestProjectSupport.addProjectFile(getProject(),
                "ubuntu/playbooks/roles/netconsole-collector/templates/netconsole-collector.service.j2", "[Service]\n");
        PsiFile tasks = AnsibleTestProjectSupport.addProjectFile(getProject(),
                "ubuntu/playbooks/roles/netconsole-collector/tasks/template-path.yaml", """
                        - name: Install netconsole collector service
                          template:
                            src: netconsole-collector.service.j2
                            dest: /etc/systemd/system/netconsole-collector.service
                        """);
        AnsibleTestProjectSupport.installModuleCatalog(getProject(), """
                {"ansible.builtin.template":"Template module"}
                """, java.util.Map.of("ansible.builtin.template", """
                {"ansible.builtin.template":{"doc":{"module":"ansible.builtin.template","options":{"src":{"type":"path"}}}}}
                """));

        configureAt(tasks, "netconsole-collector.service.j2", "netconsole-collector.service.j2".length() - 1);

        YAMLScalar templatePath = findScalar(tasks, "netconsole-collector.service.j2");
        var schema = AnsibleModels.MODEL_PROCESSOR.locate(templatePath);
        assertNotNull("Template src schema", schema);
        assertTrue("Template src must be navigable", schema.getType() instanceof ReferenceProvider);
        assertTrue(myFixture.getReferenceAtCaretPositionWithAssertion().isReferenceTo(target));
    }

    public void testTemplatePathResolvesBeforeModuleDetailsFinishLoading() throws Exception {
        PsiFile target = AnsibleTestProjectSupport.addProjectFile(getProject(),
                "ubuntu/playbooks/roles/netconsole-collector/templates/netconsole-collector.service.j2", "[Service]\n");
        PsiFile tasks = AnsibleTestProjectSupport.addProjectFile(getProject(),
                "ubuntu/playbooks/roles/netconsole-collector/tasks/template-pending.yaml", """
                        - name: Install netconsole collector service
                          template:
                            src: netconsole-collector.service.j2
                        """);
        var pendingDetails = AnsibleTestProjectSupport.installModuleListingWithoutDetails(getProject(),
                "{\"ansible.builtin.template\":\"Template module\"}");

        configureAt(tasks, "netconsole-collector.service.j2", "netconsole-collector.service.j2".length() - 1);
        YAMLScalar templatePath = findScalar(tasks, "netconsole-collector.service.j2");
        var schema = AnsibleModels.MODEL_PROCESSOR.locate(templatePath);
        assertNotNull("Template src schema must be available while module details load", schema);
        assertTrue("Template src must be navigable while module details load",
                schema.getType() instanceof ReferenceProvider);
        assertTrue(myFixture.getReferenceAtCaretPositionWithAssertion().isReferenceTo(target));

        pendingDetails.complete(new AnsibleCommandResult(0, """
                {"ansible.builtin.template":{"doc":{"module":"ansible.builtin.template","options":{"src":{"type":"path"}}}}}
                """));
    }

    private YAMLScalar findScalar(PsiFile file, String value) {
        var scalars = PsiTreeUtil.findChildrenOfType(file, YAMLScalar.class);
        return scalars.stream().filter(scalar -> scalar.getTextValue().trim().equals(value)).findFirst()
                .orElseThrow(() -> new AssertionError("Could not find scalar " + value + " in "
                        + scalars.stream().map(YAMLScalar::getTextValue).toList()));
    }

    private void warmSchemaAtCaret(PsiFile file, String value) {
        YAMLScalar scalar = findScalar(file, value);
        assertNotNull(AnsibleModels.MODEL_PROCESSOR.locate(scalar));
    }

    private void configureAt(PsiFile file, String text, int offsetWithinText) {
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        int offset = file.getText().indexOf(text) + offsetWithinText;
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
    }

    private void assertEditorNavigatedTo(PsiFile target) {
        var selectedFiles = FileEditorManager.getInstance(getProject()).getSelectedFiles();
        assertTrue("Go to Declaration did not select " + target.getVirtualFile().getPath()
                        + "; selected files: " + java.util.Arrays.toString(selectedFiles),
                java.util.Arrays.asList(selectedFiles).contains(target.getVirtualFile()));
    }
}
