package ir.msdehghan.plugins.ansible;

import com.intellij.psi.PsiReference;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class TestMoleculeDockerfileReference extends BasePlatformTestCase {
    public void testDockerfilePathNavigatesRelativeToScenario() {
        var dockerfile = myFixture.addFileToProject(
                "roles/node-exporter/molecule/default/Dockerfile.j2", "FROM fedora:44\n");
        var molecule = myFixture.addFileToProject(
                "roles/node-exporter/molecule/default/molecule.yml",
                "driver:\n  name: docker\nplatforms:\n  - name: node-exporter\n    dockerfile: Dockerfile.j2\n");

        myFixture.configureFromExistingVirtualFile(molecule.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(molecule.getText().indexOf("Dockerfile.j2") + 2);

        PsiReference reference = myFixture.getReferenceAtCaretPositionWithAssertion();
        assertEquals(dockerfile.getVirtualFile(), reference.resolve().getContainingFile().getVirtualFile());
    }
}
