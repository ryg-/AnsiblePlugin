package ir.msdehghan.plugins.ansible.model.ansible.role;

import ir.msdehghan.plugins.ansible.model.yml.YamlTypes;
import ir.msdehghan.plugins.ansible.model.yml.type.YamlMappingType;
import ir.msdehghan.plugins.ansible.reference.RoleTaskEntryReference;
import ir.msdehghan.plugins.ansible.model.yml.type.api.ReferenceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLScalar;
import com.intellij.psi.PsiReference;

import java.util.Collection;
import java.util.List;

public final class RoleInvocationType extends YamlMappingType {
    public static final RoleInvocationType TYPE = new RoleInvocationType();

    private RoleInvocationType() {
        super("Role Invocation", 7);
        addField("name").setRequired().setType(RoleNameType.TYPE)
                .setDescription("Name of the role to include or import.");
        addField("tasks_from").setType(YamlTypes.STRING);
        addField("vars_from").setType(YamlTypes.STRING);
        addField("defaults_from").setType(YamlTypes.STRING);
        addField("handlers_from").setType(YamlTypes.STRING);
        addField("allow_duplicates").setType(YamlTypes.BOOLEAN);
    }

    private static final class RoleNameType extends ir.msdehghan.plugins.ansible.model.yml.type.YamlType implements ReferenceProvider {
        private static final RoleNameType TYPE = new RoleNameType();

        private RoleNameType() {
            super("Role Name");
        }

        @Override
        public @NotNull Collection<PsiReference> getReferences(YAMLScalar scalar) {
            return List.of(new RoleTaskEntryReference(scalar));
        }
    }
}
