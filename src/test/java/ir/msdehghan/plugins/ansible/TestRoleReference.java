package ir.msdehghan.plugins.ansible;

import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReference;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import ir.msdehghan.plugins.ansible.runtime.AnsibleTestProjectSupport;

public class TestRoleReference extends BasePlatformTestCase {
    public void testRoleNameCompletion() throws Exception {
        addRole("apache");
        addRole("hbase");
        PsiFile playbook = AnsibleTestProjectSupport.addProjectFile(getProject(), "play.yml", "- hosts: localhost\n  name: Test Role Reference\n  roles:\n    - \n");
        myFixture.configureFromExistingVirtualFile(playbook.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(playbook.getText().lastIndexOf("- ") + 2);

        myFixture.completeBasic();
        assertNotNull("Lookup must be shown", LookupManager.getInstance(myFixture.getProject()).getActiveLookup());
        assertContainsElements(myFixture.getLookupElementStrings(), "apache", "hbase");

        myFixture.type("role: ");
        myFixture.completeBasic();
        assertNotNull("Lookup must be shown", LookupManager.getInstance(myFixture.getProject()).getActiveLookup());
        assertContainsElements(myFixture.getLookupElementStrings(), "apache", "hbase");
    }

    public void testRoleNameResolve() throws Exception {
        addRole("apache");
        PsiFile playbook = AnsibleTestProjectSupport.addProjectFile(getProject(), "apache-play.yml", "- hosts: localhost\n  name: Test Role Reference\n  roles:\n    - apache\n");
        myFixture.configureFromExistingVirtualFile(playbook.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(playbook.getText().indexOf("apache") + 2);

        PsiReference reference = myFixture.getReferenceAtCaretPositionWithAssertion();
        assertEquals("apache", reference.getCanonicalText());

        PsiFile taskFile = PsiManager.getInstance(myFixture.getProject())
                .findFile(playbook.getVirtualFile().getParent().findChild("roles").findChild("apache")
                        .findChild("tasks").findChild("main.yml"));
        assertTrue(reference.isReferenceTo(taskFile));
        PsiDirectory roleDirectory = PsiManager.getInstance(myFixture.getProject())
                .findDirectory(playbook.getVirtualFile().getParent().findChild("roles").findChild("apache"));
        assertTrue("Find Usages identity remains the role directory", reference.isReferenceTo(roleDirectory));

        assertSame(reference.getElement(), reference.handleElementRename("rename"));
        assertSame(reference.getElement(), reference.bindToElement(taskFile));
    }

    private void addRole(String role) throws Exception {
        AnsibleTestProjectSupport.addProjectFile(getProject(), "roles/" + role + "/tasks/main.yml", "- debug:\n    msg: ready\n");
    }
}
