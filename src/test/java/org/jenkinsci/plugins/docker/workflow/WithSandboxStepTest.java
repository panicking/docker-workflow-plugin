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

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import hudson.model.Result;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class WithSandboxStepTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public RestartableJenkinsRule story = new RestartableJenkinsRule();
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File bin;
    private File log;
    private File state;
    private File allowSentinel;

    @Before
    public void setUp() throws Exception {
        assumeFalse("POSIX shell required", System.getProperty("os.name").toLowerCase().contains("win"));
        bin = tmp.newFolder("bin");
        log = tmp.newFile("sbx.log");
        state = new File(tmp.getRoot(), "initialized");
        allowSentinel = new File(tmp.getRoot(), "allow-sentinel");
        File script = new File(bin, "sbx");
        Files.write(script.toPath(), Arrays.asList(
                "#!/bin/sh",
                "printf '%s\\n' \"$*\" >> '" + log.getAbsolutePath() + "'",
                "case \"$1\" in",
                "  version) echo 'sbx version 0.48.0' ;;",
                "  policy)",
                "    case \"$2\" in",
                "      check)",
                "        if [ ! -f '" + state.getAbsolutePath() + "' ]; then",
                "          echo 'global network policy has not been initialized' >&2",
                "          exit 1",
                "        fi",
                "        if [ -f '" + allowSentinel.getAbsolutePath() + "' ]; then",
                "          echo '{\"allowed\": true}'",
                "        else",
                "          echo '{\"allowed\": false}'",
                "          exit 1",
                "        fi",
                "        ;;",
                "      ls)",
                "        if [ ! -f '" + state.getAbsolutePath() + "' ]; then",
                "          echo 'global network policy has not been initialized' >&2",
                "          exit 1",
                "        fi",
                "        echo '{\"rules\":[]}'",
                "        ;;",
                "      init) : > '" + state.getAbsolutePath() + "' ;;",
                "    esac",
                "    ;;",
                "  exec)",
                "    shift",
                "    workdir=",
                "    while [ $# -gt 0 ]; do",
                "      case \"$1\" in",
                "        -i) shift ;;",
                "        -u) shift 2 ;;",
                "        --workdir|-w) workdir=\"$2\"; shift 2 ;;",
                "        -e) export \"$2\"; shift 2 ;;",
                "        *) break ;;",
                "      esac",
                "    done",
                "    shift", // sandbox name
                "    if [ -n \"$workdir\" ]; then cd \"$workdir\" || exit 1; fi",
                "    exec \"$@\"",
                "    ;;",
                "esac",
                "exit 0"), StandardCharsets.UTF_8);
        assertTrue(script.setExecutable(true));
    }

    @Test
    public void happyPath() {
        story.then(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                    + "  withEnv([\"PATH+SBX=" + bin.getAbsolutePath() + "\",\n"
                    + "           \"SANDBOX_FAKE_LOG=" + log.getAbsolutePath() + "\",\n"
                    + "           \"SANDBOX_FAKE_STATE=" + state.getAbsolutePath() + "\"]) {\n"
                    + "    docker.image('maven:3.9-eclipse-temurin-17').inside(sandbox: [\n"
                    + "        networkPolicy: 'deny-all',\n"
                    + "        allowedDomains: ['repo.maven.apache.org', 'github.com'],\n"
                    + "        cpus: '2',\n"
                    + "        memory: '4g']) {\n"
                    + "      sh 'echo hello-from-sandbox'\n"
                    + "    }\n"
                    + "  }\n"
                    + "}", true));
            WorkflowRun b = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
            r.assertLogContains("hello-from-sandbox", b);

            List<String> lines = readLog();
            String all = lines.toString();
            assertTrue(all, lines.stream().anyMatch(l -> l.startsWith("create --name jenkins-")
                    && l.contains("--cpus 2") && l.contains("--memory 4g")
                    && l.contains("--template maven:3.9-eclipse-temurin-17")
                    && l.contains("shell ")));
            assertTrue(all, lines.stream().anyMatch(l -> l.startsWith("policy init deny-all")));
            assertTrue(all, lines.stream().anyMatch(l -> l.startsWith(
                    "policy allow network --sandbox jenkins-") && l.endsWith("repo.maven.apache.org")));
            assertTrue(all, lines.stream().anyMatch(l -> l.startsWith(
                    "policy allow network --sandbox jenkins-") && l.endsWith("github.com")));
            assertTrue(all, lines.stream().anyMatch(l -> l.startsWith("exec -i")));
            assertTrue(all, lines.stream().anyMatch(l -> l.startsWith("rm --force jenkins-")));
        });
    }

    @Test
    public void missingSbxFailsClosed() {
        story.then(r -> {
            File empty = tmp.newFolder("empty");
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                    + "  withEnv([\"PATH=" + empty.getAbsolutePath() + "\"]) {\n"
                    + "    withDockerContainer(image: 'maven', sandbox: [networkPolicy: 'balanced']) {\n"
                    + "      sh 'echo should-not-run'\n"
                    + "    }\n"
                    + "  }\n"
                    + "}", true));
            WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
            r.assertLogContains("'sbx' was not found", b);
            r.assertLogNotContains("should-not-run", b);
        });
    }

    @Test
    public void argsWithSandboxRejected() {
        story.then(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                    + "  withEnv([\"PATH+SBX=" + bin.getAbsolutePath() + "\",\n"
                    + "           \"SANDBOX_FAKE_LOG=" + log.getAbsolutePath() + "\",\n"
                    + "           \"SANDBOX_FAKE_STATE=" + state.getAbsolutePath() + "\"]) {\n"
                    + "    withDockerContainer(image: 'maven', args: '--link db:db', sandbox: [networkPolicy: 'balanced']) {\n"
                    + "      sh 'echo should-not-run'\n"
                    + "    }\n"
                    + "  }\n"
                    + "}", true));
            WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
            r.assertLogContains("args: is not supported with sandbox:", b);
            r.assertLogNotContains("should-not-run", b);
            assertTrue(readLog().toString(), readLog().stream().noneMatch(l -> l.startsWith("create ")));
        });
    }

    @Test
    public void bodyFailureStillRemovesSandbox() {
        story.then(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                    + "  withEnv([\"PATH+SBX=" + bin.getAbsolutePath() + "\",\n"
                    + "           \"SANDBOX_FAKE_LOG=" + log.getAbsolutePath() + "\",\n"
                    + "           \"SANDBOX_FAKE_STATE=" + state.getAbsolutePath() + "\"]) {\n"
                    + "    withDockerContainer(image: 'maven', sandbox: [networkPolicy: 'balanced']) {\n"
                    + "      sh 'exit 1'\n"
                    + "    }\n"
                    + "  }\n"
                    + "}", true));
            r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
            assertTrue(readLog().toString(), readLog().stream().anyMatch(l -> l.startsWith("rm --force jenkins-")));
        });
    }

    @Test
    public void dslArgsWithSandboxRejected() {
        story.then(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                    + "  docker.image('maven').inside(args: '--user=1', sandbox: [networkPolicy: 'balanced']) {\n"
                    + "    sh 'echo should-not-run'\n"
                    + "  }\n"
                    + "}", true));
            WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
            r.assertLogContains("args: is not supported with sandbox:", b);
            r.assertLogNotContains("should-not-run", b);
        });
    }

    @Test
    public void denyAllFailsClosedAndRemovesSandbox() throws Exception {
        assertTrue(allowSentinel.createNewFile());
        assertTrue(state.createNewFile());
        story.then(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                    + "  withEnv([\"PATH+SBX=" + bin.getAbsolutePath() + "\"]) {\n"
                    + "    withDockerContainer(image: 'maven', sandbox: [networkPolicy: 'deny-all']) {\n"
                    + "      sh 'echo should-not-run'\n"
                    + "    }\n"
                    + "  }\n"
                    + "}", true));
            WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
            r.assertLogContains("Requested deny-all", b);
            r.assertLogNotContains("should-not-run", b);
            List<String> lines = readLog();
            assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.startsWith("create ")));
            assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.startsWith("rm --force jenkins-")));
        });
    }

    @Test
    public void abortRemovesSandbox() {
        story.then(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                    + "  withEnv([\"PATH+SBX=" + bin.getAbsolutePath() + "\"]) {\n"
                    + "    withDockerContainer(image: 'maven', sandbox: [networkPolicy: 'balanced']) {\n"
                    + "      semaphore 'wait'\n"
                    + "    }\n"
                    + "  }\n"
                    + "}", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait/1", b);
            b.doStop();
            r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
            assertTrue(readLog().toString(), readLog().stream().anyMatch(l -> l.startsWith("rm --force jenkins-")));
        });
    }

    @Test
    public void restartResumesSandbox() {
        story.then(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                    + "  withEnv([\"PATH+SBX=" + bin.getAbsolutePath() + "\"]) {\n"
                    + "    withDockerContainer(image: 'maven', sandbox: [networkPolicy: 'balanced']) {\n"
                    + "      semaphore 'wait'\n"
                    + "      sh 'echo after-restart'\n"
                    + "    }\n"
                    + "  }\n"
                    + "}", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait/1", b);
        });
        story.then(r -> {
            SemaphoreStep.success("wait/1", null);
            WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b = p.getLastBuild();
            r.assertBuildStatusSuccess(r.waitForCompletion(b));
            r.assertLogContains("after-restart", b);
            assertTrue(readLog().toString(), readLog().stream().anyMatch(l -> l.startsWith("rm --force jenkins-")));
        });
    }

    private List<String> readLog() {
        try {
            return Files.readAllLines(log.toPath(), StandardCharsets.UTF_8);
        } catch (Exception x) {
            throw new AssertionError(x);
        }
    }
}
