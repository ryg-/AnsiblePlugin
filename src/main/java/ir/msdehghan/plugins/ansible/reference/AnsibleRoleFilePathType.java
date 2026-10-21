package ir.msdehghan.plugins.ansible.reference;

import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiFileSystemItem;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.ResolveResult;
import ir.msdehghan.plugins.ansible.model.yml.type.YamlFilePathType;
import ir.msdehghan.plugins.ansible.model.yml.type.YamlType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLScalar;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** A template.src/files.src path rooted in its containing Ansible role. */
public final class AnsibleRoleFilePathType extends YamlType implements ir.msdehghan.plugins.ansible.model.yml.type.api.ReferenceProvider {
    private final String sourceDirectory;

    public AnsibleRoleFilePathType(String sourceDirectory) {
        super("Role " + sourceDirectory + " Path");
        this.sourceDirectory = sourceDirectory;
    }

    @Override
    public @NotNull Collection<PsiReference> getReferences(YAMLScalar scalar) {
        VirtualFile role = roleFor(scalar);
        if (role == null || scalar.getTextValue().startsWith("/")) {
            return new YamlFilePathType("Path", false, null).getReferences(scalar);
        }
        TextRange valueRange = ElementManipulators.getValueTextRange(scalar);
        return List.of(new RoleFileReference(scalar, valueRange, role.findChild(sourceDirectory)));
    }

    private static VirtualFile roleFor(YAMLScalar scalar) {
        VirtualFile file = scalar.getContainingFile().getVirtualFile();
        return file == null ? null : RoleRootResolver.forProject(scalar.getProject()).findRoleDirectory(Path.of(file.getPath()));
    }

    private static final class RoleFileReference extends PsiReferenceBase.Poly<YAMLScalar> {
        private final VirtualFile sourceRoot;

        private RoleFileReference(YAMLScalar element, TextRange range, VirtualFile sourceRoot) {
            super(element, range, true);
            this.sourceRoot = sourceRoot;
        }

        @Override
        public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
            if (sourceRoot == null) return ResolveResult.EMPTY_ARRAY;
            String value = ElementManipulators.getValueTextRange(myElement).substring(myElement.getText());
            VirtualFile target = sourceRoot.findFileByRelativePath(value);
            PsiFileSystemItem item = target == null ? null : target.isDirectory()
                    ? PsiManager.getInstance(myElement.getProject()).findDirectory(target)
                    : PsiManager.getInstance(myElement.getProject()).findFile(target);
            return item == null ? ResolveResult.EMPTY_ARRAY : new ResolveResult[]{new PsiElementResolveResult(item)};
        }

        @Override
        public Object @NotNull [] getVariants() {
            if (sourceRoot == null) return PsiReference.EMPTY_ARRAY;
            String value = ElementManipulators.getValueTextRange(myElement).substring(myElement.getText());
            int slash = value.lastIndexOf('/');
            String parentPath = slash < 0 ? "" : value.substring(0, slash + 1);
            VirtualFile parent = sourceRoot.findFileByRelativePath(parentPath);
            if (parent == null || !parent.isDirectory()) return PsiReference.EMPTY_ARRAY;
            return java.util.Arrays.stream(parent.getChildren())
                    .map(child -> LookupElementBuilder.create(parentPath + child.getName() + (child.isDirectory() ? "/" : "")))
                    .toArray();
        }
    }
}
