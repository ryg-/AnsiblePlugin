package ir.msdehghan.plugins.ansible;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import ir.msdehghan.plugins.ansible.model.yml.type.api.ReferenceProvider;
import ir.msdehghan.plugins.ansible.reference.AnsibleVariableReference;
import ir.msdehghan.plugins.ansible.runtime.AnsibleTestProjectSupport;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLScalar;
import com.intellij.psi.util.PsiTreeUtil;
import ir.msdehghan.plugins.ansible.AnsibleUtil;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class TestAnsibleLoopSourcePathNavigation extends BasePlatformTestCase {
    public void testSrcPathUsesLoopValuesAndItemStillNavigatesToLoop() throws Exception {
        PsiElement firstFixture = myFixture.addFileToProject(
                "ubuntu/playbooks/roles/netplan/tests/fixtures/01-netcfg.yaml", "network: {}\n");
        PsiElement secondFixture = myFixture.addFileToProject(
                "ubuntu/playbooks/roles/netplan/tests/fixtures/99-netcfg.yaml", "network: {}\n");
        String taskText = """
                - name: Copy netplan fixtures
                  ansible.builtin.copy:
                    src: "{{ playbook_dir }}/../../tests/fixtures/{{ item }}"
                    dest: /etc/netplan/{{ item }}
                  loop:
                    - 01-netcfg.yaml
                    - 99-netcfg.yaml
                """;
        AnsibleTestProjectSupport.installEditorTestCatalog(getProject());
        var taskFile = AnsibleTestProjectSupport.addProjectFile(getProject(),
                "ubuntu/playbooks/roles/netplan/tasks/prepare-loop.yml", taskText);

        YAMLScalar sourceScalar = findSource(taskFile);
        var schema = AnsibleModels.MODEL_PROCESSOR.locate(sourceScalar);
        assertNotNull("Source path schema should be available", schema);
        assertTrue("Source path should use a reference provider", schema.getType() instanceof ReferenceProvider);
        assertFalse("Jinja source should provide its literal path reference",
                ((ReferenceProvider) schema.getType()).getReferences(sourceScalar).isEmpty());
        var itemVariable = AnsibleVariableReference.createReferences(sourceScalar).stream()
                .filter(reference -> reference.getCanonicalText().equals("item")).findFirst().orElseThrow();
        assertEquals("Loop item in a role task should resolve to its loop list", "loop",
                ((YAMLKeyValue) itemVariable.resolve()).getKeyText());

        configureAt(taskFile, "01-netcfg.yaml", 4);
        PsiReference loopFileReference = myFixture.getReferenceAtCaretPositionWithAssertion();
        assertTrue("A literal loop filename should navigate to its source file",
                loopFileReference instanceof PsiPolyVariantReference);
        assertTrue(loopFileReference.isReferenceTo(firstFixture));

        configureAt(taskFile, "tests/fixtures", 4);
        PsiReference sourceReference = myFixture.getReferenceAtCaretPositionWithAssertion();
        assertTrue("Literal source path should expose multiple file targets", sourceReference instanceof PsiPolyVariantReference);
        Set<PsiElement> sourceTargets = Arrays.stream(((PsiPolyVariantReference) sourceReference).multiResolve(false))
                .map(ResolveResult::getElement).collect(Collectors.toSet());
        assertEquals(Set.of(firstFixture, secondFixture), sourceTargets);

        configureAt(taskFile, "item }}", 1);
        PsiReference itemReference = myFixture.getReferenceAtCaretPositionWithAssertion();
        assertTrue(itemReference instanceof PsiPolyVariantReference);
        ResolveResult[] itemTargets = ((PsiPolyVariantReference) itemReference).multiResolve(false);
        assertEquals(1, itemTargets.length);
        assertTrue(itemTargets[0].getElement() instanceof YAMLKeyValue pair && pair.getKeyText().equals("loop"));
    }

    public void testSrcPathWithoutLoopResolvesStaticSuffix() throws Exception {
        PsiElement fixture = myFixture.addFileToProject("roles/netplan/tests/fixtures/01-netcfg.yaml", "network: {}\n");
        AnsibleTestProjectSupport.installEditorTestCatalog(getProject());
        var taskFile = AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/netplan/tasks/prepare-static.yml", """
                - name: Copy one fixture
                  ansible.builtin.copy:
                    src: "{{ playbook_dir }}/../../tests/fixtures/01-netcfg.yaml"
                    dest: /etc/netplan/01-netcfg.yaml
                """);

        YAMLScalar sourceScalar = findSource(taskFile);
        var schema = AnsibleModels.MODEL_PROCESSOR.locate(sourceScalar);
        assertNotNull("Source path schema should be available", schema);
        assertTrue("Source path should use a reference provider", schema.getType() instanceof ReferenceProvider);
        assertFalse("Jinja source should provide its literal path reference",
                ((ReferenceProvider) schema.getType()).getReferences(sourceScalar).isEmpty());

        configureAt(taskFile, "tests/fixtures", 4);
        PsiReference reference = myFixture.getReferenceAtCaretPositionWithAssertion();
        assertTrue(reference instanceof PsiPolyVariantReference);
        assertEquals(Set.of(fixture), Arrays.stream(((PsiPolyVariantReference) reference).multiResolve(false))
                .map(ResolveResult::getElement).collect(Collectors.toSet()));
    }

    public void testDuplicateSuffixMatchesRemainSeparateTargets() throws Exception {
        PsiElement firstFixture = myFixture.addFileToProject(
                "roles/first/tests/fixtures/shared.yaml", "value: first\n");
        PsiElement secondFixture = myFixture.addFileToProject(
                "roles/second/tests/fixtures/shared.yaml", "value: second\n");
        AnsibleTestProjectSupport.installEditorTestCatalog(getProject());
        var taskFile = AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/netplan/tasks/prepare-duplicate.yml", """
                - name: Copy shared fixture
                  ansible.builtin.copy:
                    src: "{{ playbook_dir }}/../../tests/fixtures/{{ item }}"
                    dest: /etc/netplan/copied.yaml
                  loop:
                    - shared.yaml
                """);
        YAMLScalar loopScalar = PsiTreeUtil.findChildrenOfType(taskFile, YAMLScalar.class).stream()
                .filter(scalar -> scalar.getTextValue().equals("shared.yaml"))
                .findFirst().orElseThrow();
        assertTrue("Loop task should be recognized as Ansible", AnsibleUtil.isInAnsibleFile(loopScalar));
        YAMLKeyValue declaredLoop = AnsibleVariableReference.loopDefinition(loopScalar, "item");
        assertNotNull("Loop scalar should belong to the task's item declaration", declaredLoop);
        assertTrue("Source path should be associated with this loop",
                AnsibleVariableReference.loopDefinition(findSource(taskFile), "item").isEquivalentTo(declaredLoop));
        assertTrue("PSI should expose a file reference on the loop value",
                Arrays.stream(loopScalar.getReferences()).anyMatch(reference -> reference.getClass().getSimpleName()
                        .equals("AnsibleSourcePathReference")));

        configureAt(taskFile, "tests/fixtures", 4);
        PsiPolyVariantReference reference = (PsiPolyVariantReference) myFixture.getReferenceAtCaretPositionWithAssertion();
        assertEquals(Set.of(firstFixture, secondFixture), Arrays.stream(reference.multiResolve(false))
                .map(ResolveResult::getElement).collect(Collectors.toSet()));

        configureAt(taskFile, "shared.yaml", 2);
        PsiPolyVariantReference loopItemReference = (PsiPolyVariantReference) myFixture.getReferenceAtCaretPositionWithAssertion();
        assertEquals("The loop filename should expose every matching file", Set.of(firstFixture, secondFixture),
                Arrays.stream(loopItemReference.multiResolve(false)).map(ResolveResult::getElement).collect(Collectors.toSet()));
    }

    public void testDynamicLoopValuesDoNotGuessSourceTargets() throws Exception {
        myFixture.addFileToProject("roles/netplan/tests/fixtures/01-netcfg.yaml", "network: {}\n");
        AnsibleTestProjectSupport.installEditorTestCatalog(getProject());
        var taskFile = AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/netplan/tasks/prepare-dynamic.yml", """
                - name: Copy dynamic fixture
                  ansible.builtin.copy:
                    src: "{{ playbook_dir }}/../../tests/fixtures/{{ item }}"
                    dest: /etc/netplan/01-netcfg.yaml
                  loop: "{{ fixture_names }}"
                """);

        configureAt(taskFile, "tests/fixtures", 4);
        PsiPolyVariantReference reference = (PsiPolyVariantReference) myFixture.getReferenceAtCaretPositionWithAssertion();
        assertEquals("Unexpected target from " + reference.getClass().getName() + " for " + reference.getCanonicalText(),
                0, reference.multiResolve(false).length);
    }

    public void testMissingLoopTargetAndRemoteDestinationRemainUnlinked() throws Exception {
        AnsibleTestProjectSupport.installEditorTestCatalog(getProject());
        var taskFile = AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/netplan/tasks/prepare-missing.yml", """
                - name: Copy missing fixture
                  ansible.builtin.copy:
                    src: "{{ playbook_dir }}/../../tests/fixtures/{{ item }}"
                    dest: /etc/netplan/missing.yaml
                  loop:
                    - missing.yaml
                """);

        configureAt(taskFile, "tests/fixtures", 4);
        PsiPolyVariantReference sourceReference = (PsiPolyVariantReference) myFixture.getReferenceAtCaretPositionWithAssertion();
        assertEquals(0, sourceReference.multiResolve(false).length);

        configureAt(taskFile, "/etc/netplan/missing.yaml", 4);
        assertNull("Remote copy destination should not link to a local file",
                myFixture.getReferenceAtCaretPosition());
    }

    private void configureAt(com.intellij.psi.PsiFile file, String text, int offsetWithinText) {
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        int offset = file.getText().indexOf(text) + offsetWithinText;
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
    }

    private YAMLScalar findSource(com.intellij.psi.PsiFile file) {
        return PsiTreeUtil.findChildrenOfType(file, YAMLScalar.class).stream()
                .filter(scalar -> scalar.getTextValue().contains("playbook_dir"))
                .findFirst().orElseThrow();
    }
}
