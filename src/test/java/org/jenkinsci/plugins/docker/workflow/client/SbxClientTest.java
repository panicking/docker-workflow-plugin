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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.util.ArgumentListBuilder;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.jenkinsci.plugins.docker.workflow.SandboxPolicy;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SbxClientTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Launcher launcher;
    private EnvVars env;
    private File log;
    private File state;

    @Before
    public void setUp() throws Exception {
        Assume.assumeFalse("POSIX shell required", System.getProperty("os.name").toLowerCase().contains("win"));
        launcher = new Launcher.LocalLauncher(StreamTaskListener.NULL);
        log = tmp.newFile("sbx.log");
        state = new File(tmp.getRoot(), "initialized");
        File bin = tmp.newFolder("bin");
        File script = new File(bin, "sbx");
        Files.write(script.toPath(), Arrays.asList(
                "#!/bin/sh",
                "printf '%s\\n' \"$*\" >> \"$SBX_LOG\"",
                "case \"$1\" in",
                "  version) echo 'sbx version 0.48.0' ;;",
                "  policy)",
                "    if [ \"$2\" = check ]; then",
                "      if [ ! -f \"$SBX_STATE\" ]; then",
                "        echo 'global network policy has not been initialized' >&2",
                "        exit 1",
                "      fi",
                "      echo '{\"allowed\": false}'",
                "      exit 1",
                "    elif [ \"$2\" = init ]; then",
                "      : > \"$SBX_STATE\"",
                "    elif [ \"$2\" = ls ]; then",
                "      if [ ! -f \"$SBX_STATE\" ]; then",
                "        echo 'global network policy has not been initialized' >&2",
                "        exit 1",
                "      fi",
                "      echo '{\"rules\":[]}'",
                "    fi",
                "    ;;",
                "esac",
                "exit 0"), StandardCharsets.UTF_8);
        assertTrue(script.setExecutable(true));
        env = new EnvVars(System.getenv());
        env.put("PATH", bin.getAbsolutePath() + File.pathSeparator + env.get("PATH"));
        env.put("SBX_LOG", log.getAbsolutePath());
        env.put("SBX_STATE", state.getAbsolutePath());
    }

    @Test
    public void findExecutable() throws Exception {
        String executable = SbxClient.findExecutable(launcher, env);
        assertNotNull(executable);
        assertEquals(new File(tmp.getRoot(), "bin/sbx").getAbsolutePath(), new File(executable).getAbsolutePath());
    }

    @Test
    public void versionAndCheckVersion() throws Exception {
        SbxClient client = new SbxClient(launcher, new File(tmp.getRoot(), "bin/sbx").getAbsolutePath());
        assertNotNull(client.version(env));
        client.checkVersion(env);
    }

    @Test
    public void ensureGlobalPolicyInitializesOnce() throws Exception {
        SbxClient client = new SbxClient(launcher, new File(tmp.getRoot(), "bin/sbx").getAbsolutePath());
        SandboxPolicy policy = denyAll();
        client.ensureGlobalPolicy(env, policy);
        assertTrue(state.isFile());
        List<String> lines = Files.readAllLines(log.toPath(), StandardCharsets.UTF_8);
        assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.startsWith("policy init deny-all")));
    }

    @Test
    public void createAndApplyPolicy() throws Exception {
        SandboxPolicy policy = denyAll();
        policy.setCpus("2");
        policy.setMemory("4g");
        policy.setAllowedDomains(Arrays.asList("repo.maven.apache.org", "github.com"));

        SbxClient client = new SbxClient(launcher, new File(tmp.getRoot(), "bin/sbx").getAbsolutePath());
        client.ensureGlobalPolicy(env, policy);
        FilePath scratch = new FilePath(tmp.newFolder("scratch"));
        client.create(env, "test-sandbox", "maven:3.9-eclipse-temurin-17", policy,
                Arrays.asList("/ws", "/ws@tmp"), scratch);
        client.applySandboxPolicy(env, "test-sandbox", policy);

        List<String> lines = Files.readAllLines(log.toPath(), StandardCharsets.UTF_8);
        String all = lines.toString();
        assertTrue(all, lines.stream().anyMatch(l -> l.contains("create --name test-sandbox")
                && l.contains("--cpus 2") && l.contains("--memory 4g")
                && l.contains("--template maven:3.9-eclipse-temurin-17")
                && l.contains("shell /ws /ws@tmp")));
        assertTrue(all, lines.stream().anyMatch(l -> l.startsWith(
                "policy allow network --sandbox test-sandbox --no-input repo.maven.apache.org")));
        assertTrue(all, lines.stream().anyMatch(l -> l.startsWith(
                "policy allow network --sandbox test-sandbox --no-input github.com")));
    }

    @Test
    public void rmIgnoresMissingSandbox() throws Exception {
        // The fake script exits 0 for rm, so this mainly exercises the command shape.
        SbxClient client = new SbxClient(launcher, new File(tmp.getRoot(), "bin/sbx").getAbsolutePath());
        client.rm(env, "test-sandbox");
        assertTrue(Files.readAllLines(log.toPath(), StandardCharsets.UTF_8).toString()
                .contains("rm --force test-sandbox"));
    }

    @Test
    public void buildCreateArgsTemplate() {
        SandboxPolicy policy = denyAll();
        policy.setCpus("2");
        policy.setMemory("4g");
        assertEquals(Arrays.asList("sbx", "create", "--name", "n", "--cpus", "2", "--memory", "4g",
                        "--template", "img", "--pull", "missing", "--no-progress", "--no-input",
                        "shell", "/ws", "/ws@tmp"),
                SbxClient.buildCreateArgs("sbx", "n", "img", policy, Arrays.asList("/ws", "/ws@tmp"), null).toList());
    }

    @Test
    public void buildCreateArgsKit() {
        SandboxPolicy policy = new SandboxPolicy();
        assertEquals(Arrays.asList("sbx", "create", "--name", "n", "--no-progress", "--no-input",
                        "/tmp/kit", "/ws", "/ws@tmp"),
                SbxClient.buildCreateArgs("sbx", "n", "img", policy, Arrays.asList("/ws", "/ws@tmp"), "/tmp/kit").toList());
    }

    @Test
    public void buildPolicyAndRmArgs() {
        assertEquals(Arrays.asList("sbx", "policy", "allow", "network", "--sandbox", "n",
                        "--no-input", "example.com"),
                SbxClient.buildAllowNetworkArgs("sbx", "n", "example.com").toList());
        assertEquals(Arrays.asList("sbx", "rm", "--force", "n"),
                SbxClient.buildRmArgs("sbx", "n").toList());
    }

    @Test
    public void buildExecPrefix() {
        assertEquals(Arrays.asList("sbx", "exec", "-i", "-u", "1000:1000", "--workdir", "/ws/sub",
                        "-e", "A=B", "-e", "C=D", "n"),
                SbxClient.buildExecPrefix("sbx", "n", "1000:1000", "/ws/sub", Arrays.asList("A=B", "C=D")));
        assertEquals(Arrays.asList("sbx", "exec", "-i", "n"),
                SbxClient.buildExecPrefix("sbx", "n", null, null, Arrays.asList()));
    }

    private static SandboxPolicy denyAll() {
        SandboxPolicy policy = new SandboxPolicy();
        policy.setNetworkPolicy(SandboxPolicy.DENY_ALL);
        return policy;
    }
}
