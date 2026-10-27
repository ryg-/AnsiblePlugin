package ir.msdehghan.plugins.ansible;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.editor.Document;
import com.intellij.util.ProcessingContext;
import ir.msdehghan.plugins.ansible.molecule.MoleculeEnvironmentVariables;
import org.jetbrains.annotations.NotNull;

public final class MoleculeEnvironmentVariableCompletionProvider extends CompletionProvider<CompletionParameters> {
    @Override
    protected void addCompletions(@NotNull CompletionParameters parameters,
                                  @NotNull ProcessingContext context,
                                  @NotNull CompletionResultSet result) {
        String prefix = MoleculeEnvironmentVariables.partialName(parameters.getPosition(), parameters.getOffset());
        if (prefix == null) return;
        CompletionResultSet moleculeResult = result.withPrefixMatcher(prefix);

        for (MoleculeEnvironmentVariables.Entry entry : MoleculeEnvironmentVariables.entries()) {
            if (!entry.name().startsWith(prefix)) continue;
            moleculeResult.addElement(LookupElementBuilder.create(entry.name()).withInsertHandler((insertContext, item) -> {
                Document document = insertContext.getDocument();
                int tailOffset = insertContext.getTailOffset();
                if (tailOffset >= document.getTextLength() || document.getCharsSequence().charAt(tailOffset) != '}') {
                    document.insertString(tailOffset, "}");
                }
            }));
        }
    }
}
