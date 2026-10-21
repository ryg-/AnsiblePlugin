package ir.msdehghan.plugins.ansible.model.yml.type.api;

import com.intellij.openapi.project.Project;
import com.intellij.codeInsight.lookup.LookupElement;

import java.util.List;

/** A value provider whose choices depend on the Ansible installation for the current project. */
public interface ProjectAwareValueProvider extends ValueProvider {
    List<LookupElement> getValueLookups(Project project);
}
