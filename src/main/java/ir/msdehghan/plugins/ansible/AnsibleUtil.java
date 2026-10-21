package ir.msdehghan.plugins.ansible;

import com.intellij.icons.AllIcons;
import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import ir.msdehghan.plugins.ansible.reference.RoleRootResolver;
import ir.msdehghan.plugins.ansible.model.yml.YamlTypes;
import ir.msdehghan.plugins.ansible.model.yml.type.YamlType;
import ir.msdehghan.plugins.ansible.model.yml.type.api.YamlField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.yaml.psi.*;
import java.nio.file.Path;

import javax.swing.*;
import java.util.List;
import java.io.IOException;
import java.nio.file.Files;

public class AnsibleUtil {
    private AnsibleUtil() {
        // This class is Utility class.
    }

    public static boolean isInAnsibleFile(PsiElement element) {
        if (element == null || !(element.getContainingFile() instanceof YAMLFile yamlFile)) return false;
        List<YAMLDocument> documents = yamlFile.getDocuments();
        if (documents.isEmpty()) return false;
        YAMLValue topLevelValue = documents.getFirst().getTopLevelValue();
        return topLevelValue instanceof YAMLSequence || isRoleDocument(documents.getFirst());
    }

    /** True only for a Molecule scenario's own configuration file. */
    public static boolean isMoleculeConfig(PsiElement element) {
        if (element == null || !(element.getContainingFile() instanceof YAMLFile file)) return false;
        var virtualFile = file.getOriginalFile().getVirtualFile();
        if (virtualFile == null || !(virtualFile.getName().equals("molecule.yml")
                || virtualFile.getName().equals("molecule.yaml"))) return false;
        var scenarioDirectory = virtualFile.getParent();
        var moleculeDirectory = scenarioDirectory == null ? null : scenarioDirectory.getParent();
        return moleculeDirectory != null && moleculeDirectory.getName().equals("molecule");
    }

    public static boolean isRoleDocument(YAMLDocument document) {
        PsiDirectory sourceDirectory = document.getContainingFile().getOriginalFile().getContainingDirectory();
        if (sourceDirectory == null || !isRoleSourceDirectory(sourceDirectory.getName())) return false;
        PsiDirectory roleDirectory = sourceDirectory.getParentDirectory();
        var resolvedRole = RoleRootResolver.forProject(document.getProject())
                .findRoleDirectory(Path.of(sourceDirectory.getVirtualFile().getPath()));
        boolean sameRole = roleDirectory != null && sameFile(roleDirectory.getVirtualFile(), resolvedRole);
        return sameRole;
    }

    private static boolean sameFile(com.intellij.openapi.vfs.VirtualFile first,
                                    com.intellij.openapi.vfs.VirtualFile second) {
        if (first == null || second == null) return false;
        try {
            return Files.isSameFile(Path.of(first.getPath()), Path.of(second.getPath()));
        } catch (IOException ignored) {
            return first.equals(second);
        }
    }

    public static boolean isRoleSourceDirectory(String name) {
        return switch (name) {
            case "tasks", "handlers", "defaults", "vars", "templates", "files" -> true;
            default -> false;
        };
    }

    public static void appendSection(@NotNull String title, @Nullable String doc, @NotNull StringBuilder sb) {
        if (doc == null || doc.isEmpty()) return;
        sb.append(DocumentationMarkup.SECTION_HEADER_START)
                .append(title)
                .append(DocumentationMarkup.SECTION_SEPARATOR)
                .append(doc)
                .append(DocumentationMarkup.SECTION_END);
    }

    public static Icon getIcon(@NotNull YamlField.Relation relation) {
        return relation == YamlField.Relation.SEQUENCE ? AllIcons.Json.Array : AllIcons.Json.Object;
    }

    public static String getTypeText(@NotNull YamlField.Relation relation, @NotNull YamlType type) {
        if (relation == YamlField.Relation.SCALAR) {
            return type.getName();
        } else {
            return type == YamlTypes.ANY ? relation.toString() : relation.toString() + '[' + type.getName() + ']';
        }
    }
}
