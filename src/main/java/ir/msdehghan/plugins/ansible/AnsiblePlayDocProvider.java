package ir.msdehghan.plugins.ansible;

import com.intellij.lang.documentation.DocumentationProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.light.LightElement;
import ir.msdehghan.plugins.ansible.model.ansible.task.AnsibleTask;
import ir.msdehghan.plugins.ansible.model.yml.YamlModelProcessor;
import ir.msdehghan.plugins.ansible.model.yml.type.api.MappingType;
import ir.msdehghan.plugins.ansible.model.yml.type.api.YamlField;
import ir.msdehghan.plugins.ansible.molecule.MoleculeRuntimeVariables;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.yaml.YAMLLanguage;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLScalar;

import java.util.Optional;

import static ir.msdehghan.plugins.ansible.AnsibleModels.MODEL_PROCESSOR;

public class AnsiblePlayDocProvider implements DocumentationProvider {
    @Override
    public String generateDoc(PsiElement element, @Nullable PsiElement originalElement) {
        String variableDoc = variableDoc(originalElement);
        if (variableDoc == null) variableDoc = variableDoc(element);
        if (variableDoc != null) return variableDoc;
        if (element instanceof DocPsi docPsi) {
            return docPsi.field.generateDoc(element.getProject());
        } else if (element instanceof YAMLKeyValue keyValue){
            YamlModelProcessor.ElementSchemaInfo schemaInfo = MODEL_PROCESSOR.locate(keyValue);
            if (schemaInfo == null || schemaInfo.getType() == null || !(schemaInfo.getType() instanceof MappingType)) {
                return null;
            }
            MappingType mappingType = (MappingType) schemaInfo.getType();
            Optional<YamlField> field = mappingType instanceof AnsibleTask task
                    ? Optional.ofNullable(task.getFieldForProject(element.getProject(), keyValue.getKeyText()))
                    : mappingType.getFieldByName(keyValue.getKeyText());
            return field.map(value -> value.generateDoc(element.getProject())).orElse(null);
        } else if (element instanceof YAMLScalar scalar) {
            YamlModelProcessor.ElementSchemaInfo schemaInfo = MODEL_PROCESSOR.locate(scalar);
            return schemaInfo == null || schemaInfo.field() == null ? null
                    : schemaInfo.field().generateDoc(element.getProject());
        }
        return null;
    }

    private static String variableDoc(@Nullable PsiElement element) {
        YAMLScalar scalar = element instanceof YAMLScalar yamlScalar ? yamlScalar
                : com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, YAMLScalar.class, false);
        if (scalar == null) return null;
        String text = scalar.getTextValue();
        for (String name : AnsibleMagicVariables.names()) {
            if (text.matches("(?s).*\\b" + java.util.regex.Pattern.quote(name) + "\\b.*"))
                return AnsibleMagicVariables.documentation(name);
        }
        for (String name : MoleculeRuntimeVariables.names()) {
            if (text.matches("(?s).*\\b" + java.util.regex.Pattern.quote(name) + "\\b.*"))
                return MoleculeRuntimeVariables.documentation(name);
        }
        return null;
    }

    @Override
    public @Nullable PsiElement getDocumentationElementForLookupItem(PsiManager psiManager, Object object, PsiElement element) {
        if (object instanceof YamlField field) {
            return new DocPsi(psiManager, field);
        }
        return null;
    }

    private static class DocPsi extends LightElement {
        final YamlField field;

        protected DocPsi(PsiManager manager, YamlField field) {
            super(manager, YAMLLanguage.INSTANCE);
            this.field = field;
        }

        @Override
        public String getText() {
            return this.toString();
        }

        @Override
        public String toString() {
            return "DocPsi: " + this.field.getName();
        }
    }
}
