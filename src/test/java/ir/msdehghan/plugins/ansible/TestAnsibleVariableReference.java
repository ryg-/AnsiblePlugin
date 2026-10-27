package ir.msdehghan.plugins.ansible;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import ir.msdehghan.plugins.ansible.runtime.AnsibleTestProjectSupport;
import ir.msdehghan.plugins.ansible.reference.AnsibleVariableReference;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLScalar;

import java.util.List;

public class TestAnsibleVariableReference extends BasePlatformTestCase {
    public void testNestedVarsPropertyIsNotIndexedAsAnsibleDefinition() throws Exception {
        AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/web/defaults/nested-vars.yml", "service:\n  vars:\n    port: 8080\n");
        PsiFile playbook = myFixture.addFileToProject("playbook.yml", "- hosts: web\n  tasks:\n    - debug:\n        msg: \"{{ port }}\"\n");
        configureAt(playbook, "port", 2);

        PsiPolyVariantReference reference = (PsiPolyVariantReference) myFixture.getReferenceAtCaretPositionWithAssertion();
        assertEquals("Nested user data must not become a variable definition", 0, reference.multiResolve(false).length);
    }

    public void testUsageFindsRoleAndRelatedGroupDefinitionsBeforeUnknownRole() throws Exception {
        PsiFile roleDefault = AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/web/defaults/usage-default.yml", "service_port: 8080\n");
        AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/other/defaults/other-usage-default.yml", "service_port: 9090\n");
        PsiFile groupVars = myFixture.addFileToProject("group_vars/web.yml", "service_port: 80\n");
        PsiFile playbook = myFixture.addFileToProject("playbook.yml", """
                - hosts: web
                  roles:
                    - web
                  tasks:
                    - name: Use port
                      when: service_port > 0
                """);
        configureAt(playbook, "service_port", 4);

        PsiPolyVariantReference reference = (PsiPolyVariantReference) myFixture.getReferenceAtCaretPositionWithAssertion();
        assertEquals("service_port", reference.getCanonicalText());
        PsiElement[] targets = java.util.Arrays.stream(reference.multiResolve(false))
                .map(com.intellij.psi.ResolveResult::getElement).toArray(PsiElement[]::new);

        assertTrue(targets.length >= 3);
        assertTrue("Both direct sources must precede the unrelated role", targets[0].getContainingFile() == roleDefault
                || targets[0].getContainingFile() == groupVars);
        assertTrue(targets[1].getContainingFile() == roleDefault || targets[1].getContainingFile() == groupVars);
    }

    public void testDefinitionFindsJinjaAndConditionUsages() throws Exception {
        AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/web/tasks/definition-usage.yml", """
                - name: Render config
                  debug:
                    msg: "port {{ service_port }}"
                - name: Check port
                  when: service_port > 0
                """);
        PsiFile roleDefaults = AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/web/defaults/definition-default.yml", "service_port: 8080\n");
        configureAt(roleDefaults, "service_port", 6);

        PsiPolyVariantReference reference = (PsiPolyVariantReference) myFixture.getReferenceAtCaretPositionWithAssertion();

        assertEquals("service_port", reference.getCanonicalText());
        assertTrue(reference.multiResolve(false).length >= 2);
    }

    public void testTaskVarsSetFactAndRegisterAreDefinitions() {
        myFixture.addFileToProject("playbook.yml", """
                - hosts: web
                  tasks:
                    - name: Define task variable
                      vars:
                        task_value: 1
                      debug:
                        msg: "{{ task_value }}"
                    - name: Set fact
                      ansible.builtin.set_fact:
                        fact_value: 2
                    - name: Capture result
                      command: echo ready
                      register: command_result
                    - name: Use values
                      debug:
                        msg: "{{ fact_value }} {{ command_result }}"
                """);
        PsiFile task = myFixture.addFileToProject("other.yml", "- hosts: web\n  tasks:\n    - debug:\n        msg: '{{ fact_value }}'\n");
        configureAt(task, "fact_value", 2);

        PsiPolyVariantReference reference = (PsiPolyVariantReference) myFixture.getReferenceAtCaretPositionWithAssertion();
        PsiElement[] targets = java.util.Arrays.stream(reference.multiResolve(false))
                .map(com.intellij.psi.ResolveResult::getElement).toArray(PsiElement[]::new);
        assertEquals(1, targets.length);
        assertEquals("fact_value", ((YAMLKeyValue) targets[0]).getKeyText());

        PsiFile resultUse = myFixture.addFileToProject("result-use.yml", "- hosts: web\n  tasks:\n    - debug:\n        msg: '{{ command_result }}'\n");
        configureAt(resultUse, "command_result", 2);
        reference = (PsiPolyVariantReference) myFixture.getReferenceAtCaretPositionWithAssertion();
        assertEquals("register should be indexed from task mappings", 1, reference.multiResolve(false).length);
    }

    public void testRegisteredVariableResolvesInUntilAndAssertThat() {
        PsiFile playbook = myFixture.addFileToProject("systemd.yml", """
                - hosts: localhost
                  tasks:
                    - name: Wait for systemd
                      ansible.builtin.command: systemctl is-system-running
                      register: prepare_systemd
                      until: prepare_systemd.stdout in ['running', 'degraded']
                    - name: Fail if systemd did not boot
                      ansible.builtin.assert:
                        that:
                          - prepare_systemd.stdout in ['running', 'degraded']
                        fail_msg: "systemd is not up: {{ prepare_systemd.stdout }}"
                """);
        YAMLScalar register = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(playbook, YAMLKeyValue.class).stream()
                .filter(pair -> pair.getKeyText().equals("register"))
                .map(pair -> (YAMLScalar) pair.getValue()).findFirst().orElseThrow();

        List<YAMLScalar> usages = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(playbook, YAMLScalar.class).stream()
                .filter(scalar -> scalar.getTextValue().contains("prepare_systemd.stdout"))
                .toList();
        assertEquals("until, assert.that, and fail_msg should all be indexed as uses", 3, usages.size());
        for (YAMLScalar usage : usages) {
            var reference = AnsibleVariableReference.createReferences(usage).stream()
                    .filter(candidate -> candidate.getCanonicalText().equals("prepare_systemd"))
                    .findFirst().orElse(null);
            assertNotNull("Missing reference in: " + usage.getTextValue(), reference);
            assertEquals("Each use should resolve to the register declaration", 1,
                    ((PsiPolyVariantReference) reference).multiResolve(false).length);
            assertSame(register, ((PsiPolyVariantReference) reference).multiResolve(false)[0].getElement());
        }
    }

    public void testJinjaVariablesAreReferencesInsideInterpolatedFilePathStrings() {
        String text = """
                - hosts: localhost
                  tasks:
                    - name: Copy the serverbase netplan fixtures
                      ansible.builtin.copy:
                        src: "{{ playbook_dir }}/../../tests/fixtures/{{ item }}"
                        dest: /etc/netplan/{{ item }}
                      loop:
                        - 01-netcfg.yaml
                """;
        PsiFile playbook = myFixture.addFileToProject("playbooks/site.yml", text);
        myFixture.configureFromExistingVirtualFile(playbook.getVirtualFile());

        for (String variable : List.of("playbook_dir", "item")) {
            int offset = text.indexOf(variable) + 2;
            myFixture.getEditor().getCaretModel().moveToOffset(offset);
            PsiReference reference = myFixture.getReferenceAtCaretPositionWithAssertion();
            assertEquals("Ctrl+B should pick the Jinja variable within an interpolated path", variable,
                    reference.getCanonicalText());
            if (variable.equals("item")) {
                assertTrue(reference instanceof PsiPolyVariantReference);
                PsiElement[] targets = java.util.Arrays.stream(((PsiPolyVariantReference) reference).multiResolve(false))
                        .map(com.intellij.psi.ResolveResult::getElement).toArray(PsiElement[]::new);
                assertEquals("The implicit item variable should link back to this task's loop", 1, targets.length);
                assertTrue(targets[0] instanceof YAMLKeyValue pair && pair.getKeyText().equals("loop"));
            }
        }
    }

    public void testYamlInventoryVarsAreDefinitions() {
        myFixture.addFileToProject("inventory/hosts.yml", """
                all:
                  vars:
                    inventory_value: 8080
                  children:
                    web:
                      vars:
                        web_value: enabled
                """);
        PsiFile playbook = myFixture.addFileToProject("playbook.yml", "- hosts: web\n  tasks:\n    - debug:\n        msg: '{{ inventory_value }} {{ web_value }}'\n");
        configureAt(playbook, "inventory_value", 2);
        PsiPolyVariantReference reference = (PsiPolyVariantReference) myFixture.getReferenceAtCaretPositionWithAssertion();
        assertEquals("inventory_value", reference.getCanonicalText());
        assertEquals(1, reference.multiResolve(false).length);

        configureAt(playbook, "web_value", 2);
        reference = (PsiPolyVariantReference) myFixture.getReferenceAtCaretPositionWithAssertion();
        assertEquals("web_value", reference.getCanonicalText());
        assertEquals(1, reference.multiResolve(false).length);
    }

    public void testNestedRegisterPropertyIsNotIndexedAsAnsibleDefinition() throws Exception {
        AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/web/defaults/nested-register.yml", "service:\n  register: fake_result\n");
        PsiFile playbook = myFixture.addFileToProject("playbook.yml", "- hosts: web\n  tasks:\n    - debug:\n        msg: '{{ fake_result }}'\n");
        configureAt(playbook, "fake_result", 2);

        PsiPolyVariantReference reference = (PsiPolyVariantReference) myFixture.getReferenceAtCaretPositionWithAssertion();
        assertEquals("Nested user data must not become a register definition", 0, reference.multiResolve(false).length);
    }

    private void configureAt(PsiFile file, String text, int offsetWithinText) {
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        int offset = file.getText().indexOf(text) + offsetWithinText;
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
    }
}
