package ir.msdehghan.plugins.ansible;

import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl;
import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationSession;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.util.ProcessingContext;
import com.intellij.psi.PsiReference;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import ir.msdehghan.plugins.ansible.reference.AnsibleJinjaTemplateReferenceProvider;
import ir.msdehghan.plugins.ansible.runtime.AnsibleTestProjectSupport;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.yaml.YAMLFileType;
import org.jetbrains.yaml.psi.YAMLFile;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLScalar;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.List;

public class TestAnsibleJinjaTemplateSupport extends BasePlatformTestCase {
    public void testJinjaAndMoleculeSemanticsHaveVisibleHighlightAttributes() {
        var template = myFixture.addFileToProject("roles/netplan/templates/network.yaml.j2",
                "vlans:\n  {{ playbook_dir | to_json }}:\n");
        myFixture.configureFromExistingVirtualFile(template.getVirtualFile());
        List<Annotation> highlights = annotateFile(template);
        int magicStart = template.getText().indexOf("playbook_dir");
        int filterStart = template.getText().indexOf("to_json");
        assertTrue("Magic variable in J2 must have its own semantic highlight. fileType=" + template.getFileType()
                        + " isTemplate=" + AnsibleUtil.isAnsibleJinjaTemplate(template)
                        + " tokens=" + AnsibleJinjaExpressionUtil.variableTokens(template.getText())
                        + " annotations=" + highlights.stream().map(info -> info.getStartOffset() + ":"
                        + info.getEndOffset() + ":" + info.getTextAttributes()).toList(),
                hasHighlight(highlights, magicStart, "ANSIBLE_JINJA_MAGIC_VARIABLE"));
        assertTrue("Jinja filter must have its own semantic highlight",
                hasHighlight(highlights, filterStart, "ANSIBLE_JINJA_FILTER"));

        var molecule = myFixture.addFileToProject("roles/netplan/molecule/default/molecule.yml",
                "provisioner:\n  name: ansible\n");
        myFixture.configureFromExistingVirtualFile(molecule.getVirtualFile());
        highlights = annotateFile(molecule);
        int implementationStart = molecule.getText().indexOf("ansible");
        assertTrue("Molecule provisioner implementation must have a semantic highlight",
                hasHighlight(highlights, implementationStart, "ANSIBLE_MOLECULE_IMPLEMENTATION"));

        for (String keyName : List.of("ANSIBLE_JINJA_VARIABLE", "ANSIBLE_JINJA_FILTER",
                "ANSIBLE_JINJA_MAGIC_VARIABLE", "ANSIBLE_MOLECULE_IMPLEMENTATION")) {
            TextAttributesKey key = TextAttributesKey.find(keyName);
            assertNotNull("Missing explicit default color for " + keyName, key);
            assertNotNull("Default color should be visible in themes without an override: " + keyName,
                    key.getDefaultAttributes().getForegroundColor());
        }
    }

    private static List<Annotation> annotateFile(PsiFile file) {
        var holder = new AnnotationHolderImpl(new AnnotationSession(file), true);
        AnsibleJinjaTemplateAnnotator annotator = new AnsibleJinjaTemplateAnnotator();
        holder.runAnnotatorWithContext(file, annotator);
        return List.copyOf(holder);
    }

    private static boolean hasHighlight(List<Annotation> highlights, int startOffset, String attributeName) {
        return highlights.stream().anyMatch(info -> info.getStartOffset() == startOffset
                && info.getTextAttributes() != null
                && info.getTextAttributes().getExternalName().equals(attributeName));
    }

    public void testVariableDefinitionFindsJ2TemplateUsage() throws Exception {
        var defaults = AnsibleTestProjectSupport.addProjectFile(getProject(),
                "playbooks/roles/hetzner-labs-dns/defaults/main.yaml", "hetzner_labs_dns_interface: enp1s0\n");
        var template = AnsibleTestProjectSupport.addProjectFile(getProject(),
                "playbooks/roles/hetzner-labs-dns/templates/network.yaml.j2",
                "vlans:\n  {{ hetzner_labs_dns_interface | to_json }}:\n");
        YAMLKeyValue definition = PsiTreeUtil.findChildrenOfType(defaults, YAMLKeyValue.class).stream()
                .filter(pair -> pair.getKeyText().equals("hetzner_labs_dns_interface"))
                .findFirst().orElseThrow();

        myFixture.configureFromExistingVirtualFile(defaults.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(definition.getTextRange().getStartOffset());
        PsiReference reference = myFixture.getReferenceAtCaretPositionWithAssertion();
        var targets = ((PsiPolyVariantReference) reference).multiResolve(false);
        assertTrue("Variable declaration should list the Jinja template as a usage: "
                        + java.util.Arrays.stream(targets).map(target -> target.getElement().getContainingFile().getVirtualFile().getPath()).toList(),
                java.util.Arrays.stream(targets).anyMatch(target -> target.getElement().getContainingFile() == template));

        myFixture.configureFromExistingVirtualFile(template.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(template.getText().indexOf("hetzner_labs_dns_interface"));
        reference = myFixture.getReferenceAtCaretPositionWithAssertion();
        targets = ((PsiPolyVariantReference) reference).multiResolve(false);
        assertTrue("Jinja template should resolve back to its default declaration",
                java.util.Arrays.stream(targets).anyMatch(target -> target.getElement().getContainingFile() == defaults));
    }

    public void testJ2TemplateReferencesUseOriginalSourceText() {
        assertSame(YAMLFileType.YML, FileTypeManager.getInstance().getFileTypeByFileName("network.yaml.j2"));
        var template = myFixture.addFileToProject("roles/netplan/templates/network.yaml.j2", """
                network:
                  vlans:
                    {{ hetzner_labs_dns_interface | to_json }}:
                      nameservers:
                        search: {{ hetzner_labs_dns_routing_domains | to_json }}
                """);

        assertTrue(template instanceof YAMLFile);
        List<String> scalarTexts = PsiTreeUtil.findChildrenOfType(template, YAMLScalar.class).stream()
                .map(YAMLScalar::getTextValue).toList();
        assertTrue("Jinja variable should remain in PSI scalars: " + scalarTexts,
                scalarTexts.stream().anyMatch(value -> value.contains("hetzner_labs_dns_interface")));
        String interfaceScalar = scalarTexts.stream()
                .filter(value -> value.contains("hetzner_labs_dns_interface")).findFirst().orElseThrow();
        String source = template.getText();
        assertEquals(2, AnsibleJinjaExpressionUtil.filterTokens(source).size());
        assertTrue(AnsibleJinjaExpressionUtil.variableTokens(source).stream()
                .anyMatch(token -> token.name().equals("hetzner_labs_dns_interface")));

        int variableOffset = source.indexOf("hetzner_labs_dns_interface");
        YAMLScalar expressionScalar = PsiTreeUtil.findChildrenOfType(template, YAMLScalar.class).stream()
                .filter(scalar -> scalar.getTextRange().getStartOffset() <= variableOffset
                        && scalar.getTextRange().getEndOffset() >= variableOffset + "hetzner_labs_dns_interface".length())
                .findFirst().orElseThrow();
        assertTrue("Role template should be recognized as Ansible", AnsibleUtil.isAnsibleJinjaTemplate(expressionScalar));
        PsiReference[] references = new AnsibleJinjaTemplateReferenceProvider()
                .getReferencesByElement(expressionScalar, new ProcessingContext());
        assertTrue("Jinja variable should have a navigation reference", references.length > 0);
        assertEquals("hetzner_labs_dns_interface", references[0].getCanonicalText());

        myFixture.configureFromExistingVirtualFile(template.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(variableOffset);
        assertEquals("hetzner_labs_dns_interface",
                myFixture.getReferenceAtCaretPositionWithAssertion().getCanonicalText());

        int filterOffset = source.indexOf("to_json") + 2;
        assertFalse("Quick Documentation should recognize filters by source offset",
                new AnsibleFilterDocumentationTargetProvider().documentationTargets(template, filterOffset).isEmpty());
    }
}
