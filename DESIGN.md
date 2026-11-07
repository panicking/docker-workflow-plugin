Agentic Design Specification: Jenkins Docker-Workflow-Plugin & sbx Integration

This document outlines the architectural changes required to extend the Jenkins docker-workflow-plugin to support Docker Sandboxes (sbx) natively. It is structured to provide clear boundaries, data contracts, and execution lifecycles for implementation by an autonomous coding agent or engineering team.

1. System Architecture

The extension intercepts the standard Docker execution pipeline and conditionally routes it to the sbx CLI based on agent capabilities and pipeline DSL parameters. The architecture comprises four modified layers:

Domain Model: A serializable POJO (SandboxPolicy) capturing the microVM constraints.

Step Definition: Jenkins Step API (WithContainerStep) binding the Groovy DSL to the Java backend.

Groovy Bridge: DSL interceptor (Docker.groovy) ensuring backward compatibility.

Execution Client: Command dispatcher (DockerClient and Decorator) managing the sbx lifecycle (create, policy init, exec, rm).

2. API Contracts & Domain Models

2.1 SandboxPolicy (Domain Model)

The SandboxPolicy.java acts as the schema for the microVM configuration.

Property

Type

Default

Description

networkPolicy

String

"balanced"

Base egress rules ("balanced", "deny-all", "allow-all").

allowedDomains

List<String>

[]

Specific network destinations permitted for egress.

cpus

String

null

vCPU allocation for the microVM.

memory

String

null

RAM allocation (e.g., "4g").

2.2 WithContainerStep (Step Binding)

The Pipeline step requires a new @DataBoundSetter for the SandboxPolicy.

// org.jenkinsci.plugins.docker.workflow.WithContainerStep  
private SandboxPolicy sandbox;  
  
public SandboxPolicy getSandbox() { 
    return sandbox; 
}  
  
@DataBoundSetter  
public void setSandbox(SandboxPolicy sandbox) {  
    this.sandbox = sandbox;  
}  


3. Execution Lifecycle Contract

An implementing agent must sequence the sbx CLI commands in the following deterministic order during the setup phase of the step decorator:

Detection: Verify agent capability via which sbx.

Provisioning: sbx create --name <id> [--cpus <N>] [--memory <M>] -v <workspace>:/workspace

Base Policy: sbx policy init <networkPolicy> --sandbox <id>

Granular Policy: Iterate over allowedDomains calling sbx policy allow network <domain> --sandbox <id>

Execution Proxy: Substitute standard docker exec -i <id> with sbx exec -i <id> within the DecoratedLauncher.

Teardown: Ensure sbx rm -f <id> fires in the finally/teardown block.

4. Pipeline DSL Example

This syntax validates the final implementation state.

docker.image('maven:3.9-eclipse-temurin-17').inside(  
    sandbox: [  
        networkPolicy: 'deny-all',  
        allowedDomains: ['repo.maven.apache.org', 'github.com'],  
        cpus: '2',  
        memory: '4g'  
    ]  
) {  
    sh 'mvn clean test'  
}  

5. Implementation Notes (verified against the current sbx CLI)

The commands in section 3 require corrections for the local sbx CLI:

- Create: `sbx create [flags] AGENT|SANDBOX_KIT [PATH...]`. The first positional
  is an agent (the implementation uses `shell`) or a sandbox kit, not a bare
  image. `--name`, `--cpus` and `--memory` are valid.
- Workspace: host paths are positional (`PATH[:TARGET][:ro]`) and mount at the
  same absolute path by default. `-v/--volume` is cloud-only persistent volumes
  (`NAME:MOUNTPATH`), not host bind mounts. The implementation passes the
  workspace and `@tmp` paths positionally.
- Image: the implementation uses `--template <image>` (with `--pull missing`).
  Verified on sbx v0.51.0 with `alpine:3.20`: the sandbox ran Alpine 3.20.10, so
  ordinary Docker images work as templates. The fallback is a synthesized
  sandbox kit (`spec.yaml` with `kind: sandbox`, `sandbox.image`), selected
  with `SbxClient.USE_SANDBOX_KIT`.
- Policy lifecycle: `sbx policy init allow-all|balanced|deny-all` is host-global
  and one-time; `--sandbox` on `init` is cloud-only. The implementation
  initializes it only on a host with no policy (before `create`), never runs
  `policy reset`, and for `deny-all` verifies the effective policy with
  `sbx policy check network` before allowing domains. Per-sandbox rules use
  `sbx policy allow network --sandbox <name> <domain>`.
  `sbx policy check network` exits 1 for a denied target while still printing a
  valid JSON decision; the implementation therefore probes initialization with
  `sbx policy ls --json` and parses the check JSON regardless of exit status.
  `sbx rm` removed the sandbox-scoped rules in testing. A fresh-host
  `policy init` remains untested because resetting the existing host policy was
  not authorized.
- Exec/teardown: verified `sbx exec -i -u 1000:1000 --workdir ... -e ...`,
  exit-code propagation, and same-path mounts including paths with spaces;
  teardown uses `sbx rm --force <name>`.
- Scope: Unix agents only; a missing sbx fails the build rather than falling
  back to Docker.
- Verified on 2026-11-07 against sbx v0.51.0 on Linux; the host policy was
  balanced, so `deny-all` was exercised through its fail-closed path.

