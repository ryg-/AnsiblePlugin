package ir.msdehghan.plugins.ansible;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import ir.msdehghan.plugins.ansible.reference.AnsibleVariableReference;
import org.jetbrains.yaml.psi.YAMLScalar;

import java.util.Arrays;

public class TestAnsibleMagicVariables extends BasePlatformTestCase {
    public void testPlaybookDirHasQuickDocumentation() throws Exception {
        String text = "- hosts: localhost\n  tasks:\n    - debug:\n        msg: \"{{ playbook_dir }}\"\n";
        PsiFile file = myFixture.addFileToProject("playbooks/site.yml", text);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        int offset = text.indexOf("playbook_dir") + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(offset);

        String documentation = new AnsiblePlayDocProvider().generateDoc(
                file.findElementAt(offset), file.findElementAt(offset));

        assertNotNull(documentation);
        assertTrue(documentation, documentation.contains("Ansible magic variable"));
        assertTrue(documentation, documentation.contains("directory of the playbook currently being executed"));
    }

    public void testMagicVariablesAreSuggestedInsideJinja() throws Exception {
        String text = "- hosts: localhost\n  tasks:\n    - debug:\n        msg: \"{{ playbook_ }}\"\n";
        PsiFile file = myFixture.addFileToProject("playbooks/site.yml", text);
        YAMLScalar scalar = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, YAMLScalar.class).stream()
                .filter(value -> value.getTextValue().contains("playbook_"))
                .findFirst().orElseThrow();

        Object[] variants = Arrays.stream(AnsibleVariableReference.createReferences(scalar).toArray(PsiReference[]::new))
                .filter(reference -> reference.getCanonicalText().equals("playbook_"))
                .findFirst().orElseThrow().getVariants();

        assertTrue(Arrays.stream(variants).filter(LookupElement.class::isInstance).map(LookupElement.class::cast)
                .anyMatch(variant -> variant.getLookupString().equals("playbook_dir")));
        assertTrue("Magic-variable lookup should include all documented names",
                Arrays.stream(variants).filter(LookupElement.class::isInstance).map(LookupElement.class::cast)
                        .anyMatch(variant -> variant.getLookupString().equals("inventory_hostname")));
    }

    public void testPlaybookDirQuickDocumentationInsideInterpolatedPath() throws Exception {
        String text = """
                - hosts: localhost
                  tasks:
                    - name: Copy fixture
                      ansible.builtin.copy:
                        src: "{{ playbook_dir }}/../../tests/fixtures/{{ item }}"
                        dest: /etc/netplan/{{ item }}
                      loop: [fixture.yaml]
                """;
        PsiFile file = myFixture.addFileToProject("playbooks/site.yml", text);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        int offset = text.indexOf("playbook_dir") + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(offset);

        YAMLScalar scalar = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, YAMLScalar.class).stream()
                .filter(value -> value.getTextValue().contains("playbook_dir"))
                .findFirst().orElseThrow();
        assertTrue(AnsibleUtil.isInAnsibleFile(scalar));
        assertTrue(AnsibleVariableReference.hasReferences(scalar));
        assertEquals(1, new AnsibleMagicVariableDocumentationTargetProvider().documentationTargets(file, offset).size());

        String documentation = new AnsiblePlayDocProvider().generateDoc(scalar, scalar);

        assertNotNull("Quick Documentation should be available on a magic variable inside a path template", documentation);
        assertTrue(documentation, documentation.contains("Ansible magic variable"));
        assertTrue(documentation, documentation.contains("directory of the playbook currently being executed"));
    }
}
