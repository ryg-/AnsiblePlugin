package ir.msdehghan.plugins.ansible.reference;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.PsiReferenceBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLMapping;
import org.jetbrains.yaml.psi.YAMLScalar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Navigates a role invocation to its selected task entry, retaining directory navigation as a fallback. */
public final class RoleTaskEntryReference extends PsiReferenceBase.Poly<YAMLScalar> {
    public RoleTaskEntryReference(@NotNull YAMLScalar element) {
        super(element, true);
    }

    @Override
    public @NotNull ResolveResult[] multiResolve(boolean incompleteCode) {
        List<PsiElement> targets = new ArrayList<>();
        RoleRootResolver resolver = RoleRootResolver.forProject(myElement.getProject());
        Path sourceFile = myElement.getContainingFile().getVirtualFile() == null ? null
                : Path.of(myElement.getContainingFile().getVirtualFile().getPath());
        for (Path role : resolver.resolve(getValue(), sourceFile)) {
            Path taskFile = selectedTaskFile(myElement, role);
            if (taskFile != null) {
                VirtualFile virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .refreshAndFindFileByNioFile(taskFile);
                PsiFile psiFile = virtualFile == null ? null : PsiManager.getInstance(myElement.getProject()).findFile(virtualFile);
                if (psiFile != null) targets.add(psiFile);
            } else {
                VirtualFile virtualRole = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .refreshAndFindFileByNioFile(role);
                PsiDirectory directory = virtualRole == null ? null : PsiManager.getInstance(myElement.getProject()).findDirectory(virtualRole);
                if (directory != null) targets.add(directory);
            }
        }
        return PsiElementResolveResult.createResults(targets);
    }

    @Override
    public Object @NotNull [] getVariants() {
        return new RoleNameReference(myElement).getVariants();
    }

    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) {
        return myElement;
    }

    @Override
    public PsiElement bindToElement(@NotNull PsiElement element) {
        return myElement;
    }

    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        if (super.isReferenceTo(element)) return true;
        if (!(element instanceof PsiDirectory directory)) return false;
        Path sourceFile = myElement.getContainingFile().getVirtualFile() == null ? null
                : Path.of(myElement.getContainingFile().getVirtualFile().getPath());
        for (VirtualFile role : RoleRootResolver.forProject(myElement.getProject()).resolveVirtual(getValue(), sourceFile)) {
            if (role.equals(directory.getVirtualFile())) return true;
        }
        return false;
    }

    private static Path selectedTaskFile(YAMLScalar roleName, Path roleDirectory) {
        Path tasksDirectory = roleDirectory.resolve("tasks");
        if (!Files.isDirectory(tasksDirectory)) return null;
        String requested = scalarSiblingValue(roleName, "tasks_from");
        if (requested != null && !isStaticName(requested)) return null;
        String baseName = requested == null || requested.isBlank() ? "main" : requested;
        Path relative = Path.of(baseName);
        if (relative.isAbsolute() || relative.normalize().startsWith("..")) return null;

        if (hasExtension(baseName)) {
            Path exact = tasksDirectory.resolve(relative).normalize();
            return exact.startsWith(tasksDirectory) && Files.isRegularFile(exact) ? exact : null;
        }

        List<Path> candidates = new ArrayList<>();
        for (String extension : List.of(".yml", ".yaml")) {
            Path candidate = tasksDirectory.resolve(relative + extension).normalize();
            if (candidate.startsWith(tasksDirectory) && Files.isRegularFile(candidate)) candidates.add(candidate);
        }
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static String scalarSiblingValue(YAMLScalar roleName, String key) {
        PsiElement parent = roleName.getParent();
        if (!(parent instanceof YAMLKeyValue keyValue) || !(keyValue.getParent() instanceof YAMLMapping mapping)) return null;
        return mapping.getKeyValues().stream()
                .filter(value -> value.getKeyText().equals(key))
                .map(YAMLKeyValue::getValue)
                .filter(YAMLScalar.class::isInstance)
                .map(YAMLScalar.class::cast)
                .map(YAMLScalar::getTextValue)
                .findFirst().orElse(null);
    }

    private static boolean hasExtension(String value) {
        String fileName = Path.of(value).getFileName().toString();
        return fileName.lastIndexOf('.') > 0;
    }

    private static boolean isStaticName(String value) {
        return !value.contains("{{") && !value.contains("}}") && !value.contains("{%") && !value.contains("%}");
    }
}
