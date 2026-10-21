package ir.msdehghan.plugins.ansible.model.ansible.role;

import ir.msdehghan.plugins.ansible.model.yml.DefaultYamlField;
import ir.msdehghan.plugins.ansible.model.yml.YamlTypes;
import ir.msdehghan.plugins.ansible.model.yml.type.YamlType;
import ir.msdehghan.plugins.ansible.model.yml.type.api.MappingType;
import ir.msdehghan.plugins.ansible.model.yml.type.api.YamlField;

import java.util.List;
import java.util.Optional;

/** Generic variable mapping used at the root of role defaults/vars files. */
public final class RoleVariablesType extends YamlType implements MappingType {
    private static final YamlField VALUE = DefaultYamlField.create("<variable>")
            .setType(YamlField.Relation.SCALAR, YamlTypes.ANY)
            .setType(YamlField.Relation.MAPPING, YamlTypes.ANY)
            .setType(YamlField.Relation.SEQUENCE, YamlTypes.ANY);

    public RoleVariablesType() {
        super("Role Variables");
    }

    @Override
    public List<YamlField> getFields() {
        return List.of();
    }

    @Override
    public Optional<YamlField> getFieldByName(String name) {
        return Optional.of(VALUE);
    }
}
