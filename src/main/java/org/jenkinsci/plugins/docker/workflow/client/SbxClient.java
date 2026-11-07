/*
 * The MIT License
 *
 * Copyright (c) 2026
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.docker.workflow.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.util.ArgumentListBuilder;
import hudson.util.VersionNumber;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.docker.workflow.SandboxPolicy;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Minimal client for the Docker Sandboxes ({@code sbx}) CLI.
 *
 * <p>Unlike {@link DockerClient} this client talks to the sandbox daemon, not
 * the Docker daemon. The workspace paths are passed to {@code sbx create} as
 * positional arguments, which mounts them at the same absolute path inside the
 * sandbox.
 */
public class SbxClient {

    private static final Logger LOGGER = Logger.getLogger(SbxClient.class.getName());

    /**
     * Maximum time (in seconds) for routine {@code sbx} operations.
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "mutable for scripts")
    @Restricted(NoExternalUse.class)
    public static int CLIENT_TIMEOUT = Integer.getInteger(SbxClient.class.getName() + ".CLIENT_TIMEOUT", 180);

    /**
     * Maximum time (in seconds) for {@code sbx create}, which may pull an image.
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "mutable for scripts")
    @Restricted(NoExternalUse.class)
    public static int CREATE_TIMEOUT = Integer.getInteger(SbxClient.class.getName() + ".CREATE_TIMEOUT", 600);

    /**
     * Minimum supported {@code sbx} version. {@code --no-progress} and the
     * current policy flags are available from this release.
     */
    @Restricted(NoExternalUse.class)
    public static final VersionNumber MIN_VERSION = new VersionNumber("0.48");

    /**
     * Selects how the Docker image is mapped to a sandbox. The kit form is a
     * fallback for hosts where {@code --template} does not accept an ordinary
     * Docker image; see DESIGN.md Phase 0.
     */
    @Restricted(NoExternalUse.class)
    public static final boolean USE_SANDBOX_KIT = Boolean.getBoolean(SbxClient.class.getName() + ".USE_SANDBOX_KIT");

    private static final List<String> BALANCED_SENTINELS =
            Arrays.asList("registry-1.docker.io:443", "github.com:443");
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    private final Launcher launcher;
    private final String executable;

    public SbxClient(@NonNull Launcher launcher, @NonNull String executable) {
        this.launcher = launcher;
        this.executable = executable;
    }

    /**
     * Locates {@code sbx} on the agent's PATH.
     *
     * @param env the build environment, so {@code withEnv(["PATH+SBX=..."])}
     *            can be used to point at a specific installation
     * @return the absolute path, or {@code null} if {@code sbx} is unavailable
     */
    public static @CheckForNull String findExecutable(@NonNull Launcher launcher, @NonNull EnvVars env)
            throws IOException, InterruptedException {
        if (!launcher.isUnix()) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            int status = launcher.launch()
                    .cmds("sh", "-c", "command -v sbx")
                    .envs(env)
                    .quiet(true)
                    .stdout(out)
                    .start()
                    .joinWithTimeout(CLIENT_TIMEOUT, TimeUnit.SECONDS, launcher.getListener());
            if (status != 0) {
                return null;
            }
        } catch (IOException x) {
            LOGGER.log(Level.FINE, "Unable to run 'command -v sbx'", x);
            return null;
        }
        String path = out.toString(Charset.defaultCharset()).trim();
        return path.isEmpty() ? null : path;
    }

    /**
     * Fails if the installed {@code sbx} is older than {@link #MIN_VERSION}.
     * If the version cannot be parsed, a warning is logged and execution
     * continues so a vendor-specific build string does not block the step.
     */
    public void checkVersion(@NonNull EnvVars env) throws IOException, InterruptedException {
        VersionNumber version = version(env);
        if (version == null) {
            LOGGER.log(Level.WARNING, "Could not parse the sbx version; continuing");
            return;
        }
        if (version.isOlderThan(MIN_VERSION)) {
            throw new IOException("sbx " + version + " is too old; version " + MIN_VERSION + " or newer is required");
        }
    }

    public @CheckForNull VersionNumber version(@NonNull EnvVars env) throws IOException, InterruptedException {
        LaunchResult result = launch(env, true, CLIENT_TIMEOUT, new ArgumentListBuilder(executable, "version"));
        if (result.getStatus() != 0) {
            return null;
        }
        Matcher matcher = VERSION_PATTERN.matcher(result.getOut());
        return matcher.find() ? new VersionNumber(matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3)) : null;
    }

    /**
     * Ensures a host-global network policy preset exists before creating a
     * sandbox. An initialized host is never changed and {@code policy reset} is
     * never used.
     */
    public void ensureGlobalPolicy(@NonNull EnvVars env, @NonNull SandboxPolicy policy) throws IOException, InterruptedException {
        if (isPolicyInitialized(env)) {
            LOGGER.log(Level.FINE, "Using the existing sbx network policy; requested preset {0} is not applied",
                    policy.getNetworkPolicy());
            return;
        }
        LaunchResult result = launch(env, true, CLIENT_TIMEOUT,
                new ArgumentListBuilder(executable, "policy", "init", policy.getNetworkPolicy(), "--no-input"));
        if (result.getStatus() != 0) {
            // Another build may have initialized the policy concurrently.
            if (isPolicyInitialized(env)) {
                LOGGER.log(Level.FINE, "sbx policy was initialized concurrently; continuing");
                return;
            }
            throw new IOException("Failed to initialize the sbx network policy: " + result.getErr());
        }
    }

    /**
     * Applies sandbox-scoped allow rules after the sandbox has been created.
     * For {@code deny-all}, fails unless the host policy can be confirmed to
     * deny the balanced-policy sentinels.
     */
    public void applySandboxPolicy(@NonNull EnvVars env, @NonNull String name, @NonNull SandboxPolicy policy)
            throws IOException, InterruptedException {
        if (SandboxPolicy.DENY_ALL.equals(policy.getNetworkPolicy())) {
            verifyDenyAll(env, name);
        }
        for (String domain : policy.getAllowedDomains()) {
            LaunchResult result = launch(env, true, CLIENT_TIMEOUT, buildAllowNetworkArgs(executable, name, domain));
            if (result.getStatus() != 0) {
                throw new IOException("Failed to allow domain '" + domain + "' for sandbox '" + name + "': " + result.getErr());
            }
        }
    }

    private boolean isPolicyInitialized(@NonNull EnvVars env) throws IOException, InterruptedException {
        // Use the read-only policy listing: a successful listing means the
        // policy store exists. `policy check` cannot be used here because it
        // exits 1 for a denied target while still returning a valid decision.
        LaunchResult result = launch(env, true, CLIENT_TIMEOUT,
                new ArgumentListBuilder(executable, "policy", "ls", "--json"));
        if (result.getStatus() == 0) {
            return true;
        }
        String message = (result.getErr() + " " + result.getOut()).toLowerCase(Locale.ROOT);
        if (message.contains("not been initialized") || message.contains("not initialized")) {
            return false;
        }
        throw new IOException("Could not determine the sbx network policy state: exit=" + result.getStatus()
                + ", stdout='" + result.getOut() + "', stderr='" + result.getErr() + "'");
    }

    private void verifyDenyAll(@NonNull EnvVars env, @NonNull String name) throws IOException, InterruptedException {
        for (String sentinel : BALANCED_SENTINELS) {
            LaunchResult result = launch(env, true, CLIENT_TIMEOUT,
                    new ArgumentListBuilder(executable, "policy", "check", "network", "--format", "json",
                            "--sandbox", name, sentinel));
            // A denied target exits 1 but still returns a JSON decision, so
            // parse the output rather than relying on the exit status.
            if (!result.getOut().contains("\"allowed\"")) {
                throw new IOException("Could not verify deny-all for sandbox '" + name + "': exit="
                        + result.getStatus() + ", stdout='" + result.getOut() + "', stderr='" + result.getErr() + "'");
            }
            if (policyAllows(result.getOut())) {
                throw new IOException("Requested deny-all, but the host sbx policy allows " + sentinel
                        + "; an administrator must initialize the host policy with 'sbx policy reset' followed by "
                        + "'sbx policy init deny-all' (the plugin never resets it automatically)");
            }
        }
    }

    /**
     * The {@code policy check --format json} schema has not been stable across
     * sbx releases; recognize the common allow/deny fields and fail closed on
     * anything else.
     */
    private static boolean policyAllows(@NonNull String json) throws IOException {
        String normalized = json.replaceAll("\\s+", "");
        if (normalized.contains("\"allowed\":true") || normalized.contains("\"decision\":\"allow\"")) {
            return true;
        }
        if (normalized.contains("\"allowed\":false") || normalized.contains("\"decision\":\"deny\"")) {
            return false;
        }
        throw new IOException("Unexpected output from 'sbx policy check network': " + json);
    }

    /**
     * Creates the sandbox, mounting each host path at the same absolute path.
     *
     * @return the sandbox name
     */
    public String create(@NonNull EnvVars env, @NonNull String name, @NonNull String image,
            @NonNull SandboxPolicy policy, @NonNull List<String> hostPaths, @NonNull FilePath scratchDir)
            throws IOException, InterruptedException {
        String kitPath = prepareSandboxKit(scratchDir, image);
        ArgumentListBuilder args = buildCreateArgs(executable, name, image, policy, hostPaths, kitPath);
        LaunchResult result = launch(env, true, CREATE_TIMEOUT, args);
        if (result.getStatus() != 0) {
            throw new IOException("Failed to create sandbox '" + name + "': " + result.getErr());
        }
        return name;
    }

    /**
     * Writes a temporary sandbox kit when {@link #USE_SANDBOX_KIT} is enabled,
     * for hosts where {@code --template} does not accept an ordinary image.
     */
    private static @CheckForNull String prepareSandboxKit(@NonNull FilePath scratchDir, @NonNull String image)
            throws IOException, InterruptedException {
        if (!USE_SANDBOX_KIT) {
            return null;
        }
        FilePath kitDir = scratchDir.child("sbx-kit");
        kitDir.mkdirs();
        String escaped = image.replace("\\", "\\\\").replace("\"", "\\\"");
        kitDir.child("spec.yaml").write(
                "kind: sandbox\n"
                + "sandbox:\n"
                + "  image: \"" + escaped + "\"\n"
                + "  entrypoint: [\"sleep\", \"infinity\"]\n",
                "UTF-8");
        return kitDir.getRemote();
    }

    /**
     * Removes the sandbox. A sandbox that is already gone is not an error.
     */
    public void rm(@NonNull EnvVars env, @NonNull String name) throws IOException, InterruptedException {
        LaunchResult result = launch(env, true, CLIENT_TIMEOUT, buildRmArgs(executable, name));
        if (result.getStatus() == 0) {
            return;
        }
        String message = (result.getErr() + " " + result.getOut()).toLowerCase(Locale.ROOT);
        if (message.contains("not found") || message.contains("does not exist") || message.contains("no such")) {
            LOGGER.log(Level.FINE, "Sandbox {0} was already removed", name);
            return;
        }
        throw new IOException("Failed to remove sandbox '" + name + "': " + result.getErr());
    }

    static ArgumentListBuilder buildCreateArgs(@NonNull String executable, @NonNull String name,
            @NonNull String image, @NonNull SandboxPolicy policy, @NonNull List<String> hostPaths,
            @CheckForNull String kitPath) {
        ArgumentListBuilder args = new ArgumentListBuilder(executable, "create", "--name", name);
        if (policy.getCpus() != null) {
            args.add("--cpus", policy.getCpus());
        }
        if (policy.getMemory() != null) {
            args.add("--memory", policy.getMemory());
        }
        if (kitPath == null) {
            args.add("--template", image);
            args.add("--pull", "missing");
            args.add("--no-progress", "--no-input");
            args.add("shell");
        } else {
            args.add("--no-progress", "--no-input");
            args.add(kitPath);
        }
        for (String hostPath : hostPaths) {
            args.add(hostPath);
        }
        return args;
    }

    static ArgumentListBuilder buildAllowNetworkArgs(@NonNull String executable, @NonNull String name,
            @NonNull String domain) {
        return new ArgumentListBuilder(executable, "policy", "allow", "network",
                "--sandbox", name, "--no-input", domain);
    }

    static ArgumentListBuilder buildRmArgs(@NonNull String executable, @NonNull String name) {
        return new ArgumentListBuilder(executable, "rm", "--force", name);
    }

    static List<String> buildExecPrefix(@NonNull String executable, @NonNull String name,
            @CheckForNull String whoAmI, @CheckForNull String workdir, @NonNull List<String> envEntries) {
        List<String> prefix = new ArrayList<>(Arrays.asList(executable, "exec", "-i"));
        if (whoAmI != null && !whoAmI.isEmpty()) {
            prefix.add("-u");
            prefix.add(whoAmI);
        }
        if (workdir != null) {
            prefix.add("--workdir");
            prefix.add(workdir);
        }
        for (String entry : envEntries) {
            prefix.add("-e");
            prefix.add(entry);
        }
        prefix.add(name);
        return prefix;
    }

    private LaunchResult launch(@NonNull EnvVars env, boolean quiet, int timeout, @NonNull ArgumentListBuilder args)
            throws IOException, InterruptedException {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "Executing sbx command {0}", args.toString());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LaunchResult result = new LaunchResult();
        result.setStatus(launcher.launch()
                .cmds(args)
                .envs(env)
                .quiet(quiet)
                .stdout(out)
                .stderr(err)
                .start()
                .joinWithTimeout(timeout, TimeUnit.SECONDS, launcher.getListener()));
        Charset charset = Charset.defaultCharset();
        result.setOut(out.toString(charset));
        result.setErr(err.toString(charset));
        return result;
    }
}
