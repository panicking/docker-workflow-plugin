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
package org.jenkinsci.plugins.docker.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import hudson.model.Result;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * End-to-end test against a real {@code sbx} installation.
 *
 * <p>Disabled by default: enable with {@code -Dsbx.integration=true} on a Unix
 * host with Docker Sandboxes installed and logged in.
 */
public class SbxIntegrationTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule r = new JenkinsRule();

    @BeforeClass
    public static void requireRealSbx() {
        Assume.assumeTrue("run with -Dsbx.integration=true", Boolean.getBoolean("sbx.integration"));
        Assume.assumeFalse("POSIX shell required",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        Assume.assumeTrue("sbx must be installed", findSbx() != null);
    }

    @Test
    public void denyAllFailsClosedOnBalancedHost() throws Exception {
        String sbx = findSbx();
        Assume.assumeNotNull(sbx);
        Assume.assumeTrue("requires a non-deny-all host", hostAllows("github.com:443"));
        String binDir = new File(sbx).getParent();

        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "deny-all-sandbox");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "  withEnv([\"PATH+SBX=" + binDir + "\"]) {\n"
                + "    docker.image('alpine:3.20').inside(sandbox: [networkPolicy: 'deny-all']) {\n"
                + "      sh 'echo should-not-run'\n"
                + "    }\n"
                + "  }\n"
                + "}", true));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains("Requested deny-all", b);
        r.assertLogNotContains("should-not-run", b);
        assertFalse("sandbox leaked: " + sbxLs(), sbxLs().contains("jenkins-"));
    }

    @Test
    public void realSandbox() throws Exception {
        String sbx = findSbx();
        Assume.assumeNotNull(sbx);
        String binDir = new File(sbx).getParent();

        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "real-sandbox");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "  withEnv([\"PATH+SBX=" + binDir + "\"]) {\n"
                + "    docker.image('alpine:3.20').inside(\n"
                + "        sandbox: [networkPolicy: 'balanced', allowedDomains: ['example.com'], cpus: '1', memory: '1g']) {\n"
                + "      sh 'echo hello-real-sandbox'\n"
                + "      sh 'cat /etc/alpine-release'\n"
                + "    }\n"
                + "  }\n"
                + "}", true));
        WorkflowRun b = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        r.assertLogContains("hello-real-sandbox", b);
        r.assertLogContains("3.20", b);
        assertFalse("sandbox leaked: " + sbxLs(), sbxLs().contains("jenkins-"));
    }

    private static String findSbx() {
        try {
            Process process = new ProcessBuilder("sh", "-c", "command -v sbx")
                    .redirectErrorStream(true)
                    .start();
            String path = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 && !path.isEmpty() ? path : null;
        } catch (Exception x) {
            return null;
        }
    }

    private static boolean hostAllows(String target) throws Exception {
        Process process = new ProcessBuilder("sbx", "policy", "check", "network", "--format", "json", target)
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        // A denied target exits 1 but still returns JSON; only the decision matters.
        return out.replaceAll("\\s+", "").contains("\"allowed\":true");
    }

    private static String sbxLs() throws Exception {
        Process process = new ProcessBuilder("sbx", "ls", "--json")
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(out, 0, process.waitFor());
        return out;
    }
}
