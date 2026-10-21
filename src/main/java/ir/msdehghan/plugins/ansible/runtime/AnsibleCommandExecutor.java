package ir.msdehghan.plugins.ansible.runtime;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface AnsibleCommandExecutor {
    CompletableFuture<AnsibleCommandResult> execute(String executable, List<String> arguments, String workingDirectory);
}
