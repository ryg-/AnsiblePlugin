package ir.msdehghan.plugins.ansible.reference;

import com.intellij.patterns.PatternCondition;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.util.ProcessingContext;
import ir.msdehghan.plugins.ansible.AnsibleUtil;
import ir.msdehghan.plugins.ansible.model.yml.YamlModelProcessor.ElementSchemaInfo;
import ir.msdehghan.plugins.ansible.model.yml.type.api.ReferenceProvider;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLScalar;

import static com.intellij.patterns.PlatformPatterns.psiElement;
import static ir.msdehghan.plugins.ansible.AnsibleModels.MODEL_PROCESSOR;
import static ir.msdehghan.plugins.ansible.reference.AnsibleReferenceContributor.AnsibleFilePatternCondition.isInAnsibleFile;
import static ir.msdehghan.plugins.ansible.reference.AnsibleReferenceContributor.HasAnsibleReference.isReferenceProvider;

public class AnsibleReferenceContributor extends PsiReferenceContributor {
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(psiElement(YAMLScalar.class).with(isInAnsibleFile())
                        .with(isReferenceProvider()), new AnsibleReferenceProvider());
        registrar.registerReferenceProvider(psiElement(YAMLScalar.class).with(new MoleculeDockerfilePath()),
                new MoleculeDockerfileReferenceProvider());
    }

    static class AnsibleFilePatternCondition extends PatternCondition<YAMLScalar> {
        public AnsibleFilePatternCondition() {
            super("AnsibleFileCheck");
        }

        public static AnsibleFilePatternCondition isInAnsibleFile() {
            return new AnsibleFilePatternCondition();
        }

        @Override
        public boolean accepts(@NotNull YAMLScalar yamlScalar, ProcessingContext context) {
            return AnsibleUtil.isInAnsibleFile(yamlScalar);
        }
    }

    static class HasAnsibleReference extends PatternCondition<YAMLScalar> {
        public HasAnsibleReference() {
            super("AnsibleReferenceCheck");
        }

        public static HasAnsibleReference isReferenceProvider() {
            return new HasAnsibleReference();
        }

        @Override
        public boolean accepts(@NotNull YAMLScalar yamlScalar, ProcessingContext context) {
            ElementSchemaInfo schemaInfo = MODEL_PROCESSOR.locate(yamlScalar);
            if (schemaInfo == null) return false;
            return schemaInfo.getType() instanceof ReferenceProvider;
        }
    }

    static class MoleculeDockerfilePath extends PatternCondition<YAMLScalar> {
        MoleculeDockerfilePath() { super("MoleculeDockerfilePath"); }

        @Override
        public boolean accepts(@NotNull YAMLScalar scalar, ProcessingContext context) {
            if (!AnsibleUtil.isMoleculeConfig(scalar)) return false;
            YAMLKeyValue pair = PsiTreeUtil.getParentOfType(scalar, YAMLKeyValue.class, false);
            return pair != null && pair.getValue() == scalar && pair.getKeyText().equals("dockerfile");
        }
    }
}
