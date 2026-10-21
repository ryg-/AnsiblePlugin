package ir.msdehghan.plugins.ansible.reference;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLScalar;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.nio.file.Path;

public class RoleNameReference extends PsiReferenceBase.Poly<YAMLScalar> {
    public RoleNameReference(YAMLScalar element) {
        super(element, true);
    }

    private static LookupElement createLookupElement(String name) {
        return LookupElementBuilder.create(name)
                .withIcon(AllIcons.Actions.GroupByModuleGroup)
                .withTypeText("Ansible Role");
    }

    @Override
    public @NotNull ResolveResult[] multiResolve(boolean incompleteCode) {
        List<PsiDirectory> roles = new ArrayList<>();
        Path sourceFile = myElement.getContainingFile().getVirtualFile() == null ? null
                : Path.of(myElement.getContainingFile().getVirtualFile().getPath());
        for (VirtualFile role : RoleRootResolver.forProject(myElement.getProject()).resolveVirtual(getValue(), sourceFile)) {
            PsiDirectory directory = PsiManager.getInstance(myElement.getProject()).findDirectory(role);
            if (directory != null) roles.add(directory);
        }
        return PsiElementResolveResult.createResults(roles);
    }

    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) {
        return myElement; // We do not support rename role name cause it can hurt other part of project.
    }

    @Override
    public PsiElement bindToElement(@NotNull PsiElement element) {
        return myElement; // We do not support rename role name cause it can hurt other part of project.
    }

    @Override
    public @NotNull Object[] getVariants() {
        Set<String> roles = new LinkedHashSet<>();
        Path sourceFile = myElement.getContainingFile().getVirtualFile() == null ? null
                : Path.of(myElement.getContainingFile().getVirtualFile().getPath());
        for (VirtualFile root : RoleRootResolver.forProject(myElement.getProject()).getRoleRoots(sourceFile).stream()
                .map(path -> com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                .filter(java.util.Objects::nonNull).toList()) {
            for (VirtualFile child : root.getChildren()) {
                if (child.isDirectory()) roles.add(child.getName());
            }
        }
        return roles.stream().map(RoleNameReference::createLookupElement).toArray();
    }

}
