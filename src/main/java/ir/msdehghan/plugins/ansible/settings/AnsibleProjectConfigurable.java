package ir.msdehghan.plugins.ansible.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import ir.msdehghan.plugins.ansible.runtime.AnsibleProjectService;
import ir.msdehghan.plugins.ansible.runtime.CatalogStatus;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;

public final class AnsibleProjectConfigurable implements Configurable {
    private final AnsibleProjectService service;
    private JPanel panel;
    private JTextField executableField;
    private JLabel statusLabel;
    private Consumer<AnsibleProjectService.CatalogSnapshot> statusListener;

    public AnsibleProjectConfigurable(Project project) {
        service = AnsibleProjectService.getInstance(project);
    }

    @Override
    public @Nls String getDisplayName() {
        return "Ansible";
    }

    @Override
    public @Nullable JComponent createComponent() {
        panel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.anchor = GridBagConstraints.WEST;

        constraints.gridx = 0;
        constraints.gridy = 0;
        JLabel executableLabel = new JLabel("Ansible documentation executable:");
        executableLabel.setToolTipText("Stored in your IDE user settings for this project; repository workspace files cannot select executables.");
        panel.add(executableLabel, constraints);

        executableField = new JTextField(36);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(executableField, constraints);

        statusLabel = new JLabel();
        constraints.gridx = 0;
        constraints.gridy = 1;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(statusLabel, constraints);

        JButton refreshButton = new JButton("Refresh module catalog");
        refreshButton.addActionListener(event -> service.refreshCatalog());
        constraints.gridy = 2;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        panel.add(refreshButton, constraints);

        reset();
        statusListener = this::updateStatus;
        service.addListener(statusListener);
        return panel;
    }

    @Override
    public boolean isModified() {
        return executableField != null && !service.getExecutable().equals(executableField.getText().trim());
    }

    @Override
    public void apply() {
        if (executableField != null) {
            service.setExecutable(executableField.getText());
        }
    }

    @Override
    public void reset() {
        if (executableField != null) {
            executableField.setText(service.getExecutable());
        }
        updateStatus(service.getSnapshot());
    }

    @Override
    public void disposeUIResources() {
        if (statusListener != null) {
            service.removeListener(statusListener);
            statusListener = null;
        }
        panel = null;
        executableField = null;
        statusLabel = null;
    }

    private void updateStatus(AnsibleProjectService.CatalogSnapshot snapshot) {
        if (statusLabel == null) {
            return;
        }
        String message = snapshot.message();
        String text = switch (snapshot.status()) {
            case LOADING -> "Loading module catalog…";
            case READY -> "Module catalog ready.";
            case UNAVAILABLE -> "Module catalog unavailable: " + message;
            case FAILED_WITH_LAST_GOOD -> "Refresh failed; using the last successful module catalog: " + message;
        };
        statusLabel.setText(text);
    }
}
