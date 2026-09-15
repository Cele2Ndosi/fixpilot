package com.fixpilot.service;

import com.fixpilot.config.FixPilotProperties;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Responsibility (single):
 * - Give the orchestrator a completely isolated place to reproduce a
 *   commit's test failure, apply a candidate patch, and rerun tests - and
 *   guarantee that nothing which happens inside that isolation can persist
 *   or reach the network.
 *
 * Every public method clones a fresh checkout and deletes it afterward;
 * nothing is ever reused between investigations. Every Docker invocation
 * runs with --rm --network none. This is deliberate: a patch this service
 * applies was written by a language model, not reviewed by a human, before
 * it ever gets here - the isolation boundary is not a nice-to-have.
 */
@Service
public class SandboxService {

    private final FixPilotProperties props;

    public SandboxService(FixPilotProperties props) {
        this.props = props;
    }

    /**
     * @param patchedFileContents empty for a plain reproduction run; populated
     *                            with path -> new full content for every file
     *                            touched by a successfully-applied patch, read
     *                            back from the checkout so the orchestrator can
     *                            commit exactly what was actually verified.
     */
    public record TestRunResult(boolean passed, int exitCode, String output,
                                 int testsPassed, int testsTotal,
                                 Map<String, String> patchedFileContents) {}

    /** Clones the repo at `sha` into a fresh directory and runs the full test suite. */
    public TestRunResult reproduce(String owner, String repo, String sha, String investigationId) {
        Path workDir = prepareCheckout(owner, repo, sha, investigationId);
        try {
            return runTestsInContainer(workDir, Map.of());
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * Applies a unified diff inside a fresh checkout, then reruns tests. If
     * `git apply` itself fails (the model's diff doesn't match the file
     * anymore), that's reported as a failed run without ever starting a
     * container - there is nothing to test yet.
     */
    public TestRunResult applyPatchAndRerun(String owner, String repo, String sha,
                                             String investigationId, String unifiedDiff) {
        Path workDir = prepareCheckout(owner, repo, sha, investigationId);
        try {
            Path patchFile = workDir.resolve("fixpilot.patch");
            Files.writeString(patchFile, unifiedDiff);
            ProcessResult applyResult = runCommand(List.of("git", "apply", "fixpilot.patch"), workDir.toFile());
            if (applyResult.exitCode() != 0) {
                return new TestRunResult(false, applyResult.exitCode(),
                        "Patch did not apply cleanly:\n" + applyResult.output(), 0, 0, Map.of());
            }
            Map<String, String> patchedFiles = extractPatchedFiles(unifiedDiff, workDir);
            return runTestsInContainer(workDir, patchedFiles);
        } catch (IOException e) {
            return new TestRunResult(false, -1, "Failed to write patch file: " + e.getMessage(), 0, 0, Map.of());
        } finally {
            deleteRecursively(workDir);
        }
    }

    private Path prepareCheckout(String owner, String repo, String sha, String investigationId) {
        try {
            Path base = Path.of(props.getSandbox().getWorkdir());
            Files.createDirectories(base);
            Path workDir = base.resolve(investigationId);
            String cloneUrl = "https://github.com/" + owner + "/" + repo + ".git";
            runCommand(List.of("git", "clone", cloneUrl, workDir.toString()), base.toFile());
            runCommand(List.of("git", "checkout", sha), workDir.toFile());
            return workDir;
        } catch (IOException e) {
            throw new SandboxException("Failed to prepare checkout for " + sha, e);
        }
    }

    private TestRunResult runTestsInContainer(Path workDir, Map<String, String> patchedFileContents) {
        List<String> dockerCommand = List.of(
                "docker", "run", "--rm", "--network", "none",
                "-v", workDir.toAbsolutePath() + ":/app",
                "-w", "/app",
                props.getSandbox().getDockerImage(),
                "sh", "-c", "npm ci --silent && npm test"
        );
        ProcessResult result = runCommand(dockerCommand, workDir.toFile());
        int[] counts = parseTestCounts(result.output());
        boolean passed = result.exitCode() == 0;
        return new TestRunResult(passed, result.exitCode(), result.output(), counts[0], counts[1], patchedFileContents);
    }

    /**
     * Reads back the full new content of every file the diff touched, straight
     * from the checkout `git apply` just modified. This deliberately doesn't
     * reimplement diff application - git already did that correctly - it just
     * harvests the result, which is what lets applyPatchAndRerun handle
     * multi-file patches without FixPilot needing its own patch-application
     * logic.
     */
    private Map<String, String> extractPatchedFiles(String unifiedDiff, Path workDir) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher m = Pattern.compile("^\\+\\+\\+ b/(.+)$", Pattern.MULTILINE).matcher(unifiedDiff);
        while (m.find()) {
            String relativePath = m.group(1).trim();
            Path filePath = workDir.resolve(relativePath);
            if (Files.exists(filePath)) {
                try {
                    result.put(relativePath, Files.readString(filePath));
                } catch (IOException ignored) {
                    // If we can't read a patched file back, it's excluded from the
                    // PR payload rather than failing the whole verification - the
                    // test result itself already reflects the applied patch.
                }
            }
        }
        return result;
    }

    /**
     * Best-effort extraction of "N passed / M total" from common test runner
     * output (Jest, Mocha). Falls back to {0, 0} if the format isn't
     * recognized - swap this for your sample app's actual runner if it isn't
     * one of these two; the pass/fail boolean from the exit code is always
     * correct regardless, only the counts shown in the evidence report depend
     * on this parser.
     */
    private int[] parseTestCounts(String output) {
        Matcher jest = Pattern.compile("Tests:\\s+(?:\\d+ failed, )?(\\d+) passed, (\\d+) total").matcher(output);
        if (jest.find()) {
            return new int[]{ Integer.parseInt(jest.group(1)), Integer.parseInt(jest.group(2)) };
        }
        Matcher mocha = Pattern.compile("(\\d+) passing").matcher(output);
        if (mocha.find()) {
            int passing = Integer.parseInt(mocha.group(1));
            return new int[]{ passing, passing };
        }
        return new int[]{ 0, 0 };
    }

    /**
     * Runs a command with a real, enforced wall-clock timeout. The naive
     * version of this (read the process's stdout fully, then call waitFor)
     * silently defeats the timeout: readAllBytes() blocks until the stream
     * closes, which only happens when the process exits - so a hung process
     * hangs the caller too, no matter what timeout is configured. Draining
     * the stream on a background thread while the main thread calls the
     * timed waitFor is what makes the timeout real.
     */
    private ProcessResult runCommand(List<String> command, File workingDir) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start();

            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            Thread drain = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(outputBuffer);
                } catch (IOException ignored) {
                    // Expected when destroyForcibly() closes the stream on timeout below.
                }
            });
            drain.start();

            boolean exitedInTime = process.waitFor(props.getSandbox().getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!exitedInTime) {
                process.destroyForcibly();
            }
            drain.join(2000);

            String output = outputBuffer.toString(StandardCharsets.UTF_8);
            if (!exitedInTime) {
                return new ProcessResult(-1, output + "\n[FixPilot] Timed out after "
                        + props.getSandbox().getTimeoutSeconds() + "s and was killed.");
            }
            return new ProcessResult(process.exitValue(), output);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessResult(-1, "Command failed: " + e.getMessage());
        }
    }

    private void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                            // Best-effort cleanup - a leaked temp dir is a disk-space
                            // issue, not a correctness one, and shouldn't fail an
                            // otherwise-successful investigation.
                        }
                    });
        } catch (IOException ignored) {
            // Same reasoning as above.
        }
    }

    private record ProcessResult(int exitCode, String output) {}

    public static class SandboxException extends RuntimeException {
        public SandboxException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
