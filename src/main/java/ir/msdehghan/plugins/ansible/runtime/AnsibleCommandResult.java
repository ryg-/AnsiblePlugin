package ir.msdehghan.plugins.ansible.runtime;

public record AnsibleCommandResult(int exitCode, String output, String errorOutput) {
    public AnsibleCommandResult(int exitCode, String output) {
        this(exitCode, output, "");
    }
}
