package ir.msdehghan.plugins.ansible.reference;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.util.ProcessingContext;
import ir.msdehghan.plugins.ansible.model.yml.type.YamlFilePathType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLScalar;

/** Resolves the Docker build context file relative to the Molecule scenario directory. */
public final class MoleculeDockerfileReferenceProvider extends PsiReferenceProvider {
    private static final YamlFilePathType PATH_TYPE = new YamlFilePathType("Dockerfile path", false, null);

    @Override
    public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element,
                                                           @NotNull ProcessingContext context) {
        if (!(element instanceof YAMLScalar scalar)) return PsiReference.EMPTY_ARRAY;
        return PATH_TYPE.getReferences(scalar).toArray(PsiReference.EMPTY_ARRAY);
    }
}
