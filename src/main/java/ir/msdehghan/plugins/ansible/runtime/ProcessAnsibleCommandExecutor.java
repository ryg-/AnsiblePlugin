package ir.msdehghan.plugins.ansible.runtime;

import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class ProcessAnsibleCommandExecutor implements AnsibleCommandExecutor {
    private static final long COMMAND_TIMEOUT_SECONDS = 30;
    private final long timeoutSeconds;

    ProcessAnsibleCommandExecutor() { this(COMMAND_TIMEOUT_SECONDS); }

    ProcessAnsibleCommandExecutor(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    @Override
    public CompletableFuture<AnsibleCommandResult> execute(String executable, List<String> arguments,
                                                            String workingDirectory) {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(executable);
        command.addAll(arguments);
        AtomicReference<Process> processRef = new AtomicReference<>();
        CompletableFuture<AnsibleCommandResult> result = new CompletableFuture<>();
        ScheduledFuture<?> deadline = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
            Process process = processRef.get();
            if (process != null) process.destroyForcibly();
            result.completeExceptionally(new IOException("ansible-doc command timed out after "
                    + timeoutSeconds + " seconds"));
        }, timeoutSeconds, TimeUnit.SECONDS);
        result.whenComplete((unused, error) -> {
            deadline.cancel(false);
            if (result.isCancelled()) {
                Process process = processRef.get();
                if (process != null) {
                    process.destroyForcibly();
                }
            }
        });

        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                if (workingDirectory != null && !workingDirectory.isBlank()) {
                    builder.directory(new java.io.File(workingDirectory));
                }
                configureEnvironment(builder.environment());
                Process process = builder.start();
                processRef.set(process);
                if (result.isDone()) {
                    process.destroyForcibly();
                    return;
                }
                CompletableFuture<String> output = readAsync(process.getInputStream());
                CompletableFuture<String> errorOutput = readAsync(process.getErrorStream());
                int exitCode = process.waitFor();
                result.complete(new AnsibleCommandResult(exitCode, output.join(), errorOutput.join()));
            } catch (IOException e) {
                result.completeExceptionally(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.completeExceptionally(e);
            } catch (CompletionException e) {
                result.completeExceptionally(e.getCause() == null ? e : e.getCause());
            }
        });
        return result;
    }

    static void configureEnvironment(Map<String, String> environment) {
        String localTemp = environment.get("ANSIBLE_LOCAL_TEMP");
        if (localTemp == null || localTemp.isBlank()) {
            environment.put("ANSIBLE_LOCAL_TEMP", System.getProperty("java.io.tmpdir"));
        }
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, AppExecutorUtil.getAppExecutorService());
    }
}
