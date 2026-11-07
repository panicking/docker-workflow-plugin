# Provisioning a Debian/Ubuntu Jenkins agent for Docker Sandboxes

This guide prepares a Jenkins agent (slave) that can run pipeline steps with
`docker.image(...).inside(sandbox: ...)` using Docker Sandboxes (`sbx`).

It was verified on Ubuntu 26.04 with `docker-sbx` 0.51.0 and the plugin’s
`SbxClient.MIN_VERSION` of 0.48.0. The agent runs on the host itself, not
inside a Docker container.

## Support matrix

| OS | Status |
|---|---|
| Ubuntu 24.04 LTS (noble) | Supported by Docker; use the standalone `ubuntu2404` package if the apt repository version is older than 0.48 |
| Ubuntu 26.04 (resolute) | Supported; the Docker apt repository carries 0.51.0 as of this writing |
| Debian 12/13 | **Not officially supported.** Docker’s Debian apt repository does not publish `docker-sbx`, and the standalone packages are Ubuntu-specific. Use Ubuntu for a supported setup. |

Docker Sandboxes requires:

* A 64-bit x86_64 or arm64 machine with KVM hardware virtualization enabled.
* The user running `sbx` in the `kvm` group (or equivalent ACL on `/dev/kvm`).
* Nested virtualization enabled by the hypervisor if the agent is itself a VM.
* Enough disk space for the sandbox image/template cache and per-sandbox
  volumes. A practical minimum for build agents is 4 vCPU, 8 GB RAM and 50 GB
  free disk; the default sandbox Docker volume is 10 GB.
* The plugin requires `sbx` **0.48.0 or newer**; 0.51.0 was tested.

Docker Engine is **not required for the sandbox path**: each sandbox runs its
own Docker daemon inside the microVM. Install Docker CE only if the same agent
also uses the regular container path (`docker.image(...).inside { }` without
`sandbox:`).

> **Important:** Docker Sandboxes officially targets Ubuntu 24.04+. On Ubuntu
> 24.04 the Docker apt repository may still provide an old `docker-sbx`
> (0.23.0 was observed). Check `sbx version` and install the standalone
> 0.51.0 package if necessary. The plugin will reject versions below 0.48.

## 1. Create the Jenkins user

Run all sandbox operations as the same OS user that runs the Jenkins agent.
The daemon state, login credentials and sandbox sockets live in that user’s
home directory.

```bash
sudo adduser --disabled-password --gecos "" jenkins
sudo usermod -s /bin/bash jenkins
sudo mkdir -p /home/jenkins/agent
sudo chown -R jenkins:jenkins /home/jenkins
```

## 2. Install KVM access and Java

```bash
sudo apt-get update
sudo apt-get install -y cpu-checker openjdk-21-jre-headless

# Verify hardware virtualization and /dev/kvm
sudo kvm-ok
ls -l /dev/kvm

# Add the agent user to the kvm group
sudo usermod -aG kvm jenkins

# Verify in a fresh login as jenkins
sudo -iu jenkins
test -r /dev/kvm && test -w /dev/kvm && echo "KVM access OK"
```

If `kvm-ok` reports that KVM is unavailable, enable VT-x/AMD-V in the machine
firmware and, when the agent is a VM, enable nested virtualization on its
hypervisor. `newgrp kvm` only affects the current shell; the Jenkins service
must be restarted after the group change.

## 3. Install `sbx`

### Option A: standalone package (recommended for the version floor)

Docker publishes signed standalone packages at
[github.com/docker/sbx-releases](https://github.com/docker/sbx-releases/releases).
The 0.51.0 assets include:

* `DockerSandboxes-linux-amd64-ubuntu2404.deb`
* `DockerSandboxes-linux-amd64-ubuntu2604.deb`
* `DockerSandboxes-linux-arm64-ubuntu2404.deb`
* `DockerSandboxes-linux-arm64-ubuntu2604.deb`

Choose the asset matching the Ubuntu release and architecture:

```bash
ARCH=$(dpkg --print-architecture)
# For Ubuntu 24.04:
curl -fLO "https://github.com/docker/sbx-releases/releases/download/v0.51.0/DockerSandboxes-linux-${ARCH}-ubuntu2404.deb"
sudo apt-get install -y ./DockerSandboxes-linux-${ARCH}-ubuntu2404.deb
```

For Ubuntu 26.04, replace `ubuntu2404` with `ubuntu2604`. The package installs
`/usr/bin/sbx`, the `sandboxd` runtime, the kernel/rootfs assets, and the
`mkfs.erofs`/`mkfs.ext4` helpers. Its dependencies include `ca-certificates`,
`dbus-daemon` and `gnome-keyring`.

### Option B: Docker apt repository

```bash
curl -fsSL https://get.docker.com | sudo REPO_ONLY=1 sh
sudo apt-get update
sudo apt-get install -y docker-sbx
```

`REPO_ONLY=1` adds the Docker repository without installing Docker Engine.
The `docker-sbx` package is Ubuntu-specific; the Debian repository does not
publish it.

### Verify the installation

```bash
sbx version
```

Expect a version at least 0.48.0 (0.51.0 was tested). If the apt repository
gave you an older version, install the standalone package from Option A.

## 4. Run the sandbox daemon as a user service

`docker-sbx` does not install a systemd unit. The daemon runs per user and
stores its socket and logs under `~/.local/state/sandboxes/sandboxes/sandboxd/`.

As root, enable lingering so the user manager starts at boot even when
`jenkins` is not logged in, and start the manager once if needed:

```bash
sudo loginctl enable-linger jenkins
sudo systemctl start user@$(id -u jenkins).service
```

As `jenkins`:

```bash
sudo -iu jenkins
export XDG_RUNTIME_DIR=/run/user/$(id -u)
mkdir -p ~/.config/systemd/user

cat > ~/.config/systemd/user/sandboxd.service <<'EOF'
[Unit]
Description=Docker Sandboxes daemon

[Service]
ExecStart=/usr/bin/sbx daemon start
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now sandboxd.service
```

Verify:

```bash
sudo -iu jenkins
export XDG_RUNTIME_DIR=/run/user/$(id -u)
systemctl --user status sandboxd.service
sbx daemon status
sbx diagnose
```

The daemon status prints the socket path and log location, for example:

```text
Status:  running
Socket:  /home/jenkins/.local/state/sandboxes/sandboxes/sandboxd/sandboxd.sock
Logs:    /home/jenkins/.local/state/sandboxes/sandboxes/sandboxd/daemon.log
```

If `systemctl --user` cannot reach a bus, install `dbus-user-session`, log in
again as `jenkins` (or restart the user manager), and retry.

### Alternative without systemd

If you do not use a user service, start the daemon from the agent startup or a
login script:

```bash
sbx daemon start --detach
```

This is less robust than the systemd unit; the daemon must be restarted after
upgrades or crashes.

## 5. Log in to Docker

The daemon must be running. As `jenkins`:

```bash
sudo -iu jenkins
sbx login
```

On a headless agent, use a Docker Personal Access Token instead of the browser
flow. The token needs at least **Read** scope:

```bash
read -rsp "Docker username: " DOCKER_USER; echo
read -rsp "Docker PAT: " DOCKER_PAT; echo
echo "$DOCKER_PAT" | sbx login --username "$DOCKER_USER" --password-stdin
```

Store the PAT in a root-only or `jenkins`-only file if the agent is
re-provisioned often; do not put it in shell history or a world-readable file.
`sbx diagnose` should then report `Authentication — authenticated`.

## 6. Initialize the network policy

The policy is global to the user/daemon and must exist before sandboxes can be
created:

```bash
sbx policy ls >/dev/null 2>&1 || sbx policy init deny-all
```

Use `balanced` instead of `deny-all` if you want the standard development
allow-list. For a Jenkins agent, `deny-all` is the safer baseline; each
pipeline declares its own `allowedDomains` in `sandbox: [...]`.

> **Warning:** `sbx policy reset` deletes the policy store and stops running
> sandboxes. Never run it from a build. The plugin never resets the policy
> automatically.

Verify:

```bash
sbx policy ls
# A denied target exits 1 but still prints a JSON decision; that is expected.
sbx policy check network --format json example.com || true
```

## 7. Install the Jenkins agent

If the controller launches the node over SSH, install the server and authorize
the controller’s public key as `jenkins`:

```bash
sudo apt-get install -y openssh-server
sudo -iu jenkins
mkdir -p ~/.ssh && chmod 700 ~/.ssh
# Append the controller's public key here, then:
chmod 600 ~/.ssh/authorized_keys
```

As `jenkins`, create the agent directory and secret file:

```bash
sudo -iu jenkins
mkdir -p ~/agent
# Download agent.jar from the controller:
curl -fLo ~/agent.jar https://jenkins.example.com/jnlpJars/agent.jar
# Write the node secret (from the controller) into a private file:
install -m 0600 /dev/null ~/agent-secret
# Paste the secret into ~/agent-secret with your editor
```

Install a system service:

```bash
sudo tee /etc/systemd/system/jenkins-agent.service >/dev/null <<'EOF'
[Unit]
Description=Jenkins agent
After=network-online.target
Wants=network-online.target

[Service]
User=jenkins
WorkingDirectory=/home/jenkins
Environment=HOME=/home/jenkins
Environment=PATH=/usr/local/bin:/usr/bin:/bin
ExecStart=/usr/bin/java -jar /home/jenkins/agent.jar \
  -url https://jenkins.example.com/ \
  -secret @/home/jenkins/agent-secret \
  -name sbx-agent \
  -workDir /home/jenkins/agent
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now jenkins-agent.service
sudo systemctl status jenkins-agent.service
```

On the Jenkins controller, create a node:

* **Remote root directory:** `/home/jenkins/agent`
* **Label:** `sbx` (or your chosen label)
* **Launch method:** SSH as `jenkins`, or inbound with the node secret
* **Host key verification:** configure the controller’s known-host strategy

The agent must run directly on the host. If the Jenkins agent runs inside a
Docker container, `sbx` cannot mount the host workspace into its microVMs;
run the agent on the host instead.

## 8. Verify end to end

### CLI smoke test

As `jenkins`:

```bash
sudo -iu jenkins
mkdir -p ~/sbx-smoke
echo hello-from-host > ~/sbx-smoke/hello.txt

sbx create \
  --name sbx-smoke \
  --template alpine:3.20 \
  --pull missing \
  --no-progress \
  --no-input \
  shell /home/jenkins/sbx-smoke

sbx exec -i -u "$(id -u):$(id -g)" sbx-smoke \
  sh -c 'cat /home/jenkins/sbx-smoke/hello.txt; cat /etc/alpine-release'

sbx rm --force sbx-smoke
sbx ls
```

The exec should print `hello-from-host` and the Alpine version, and `sbx ls`
must not list `sbx-smoke` afterwards.

### Jenkins pipeline

```groovy
pipeline {
    agent { label 'sbx' }
    stages {
        stage('Sandbox smoke test') {
            steps {
                docker.image('alpine:3.20').inside(
                    sandbox: [
                        networkPolicy: 'balanced',
                        cpus: '1',
                        memory: '1g'
                    ]
                ) {
                    sh 'cat /etc/alpine-release'
                }
            }
        }
    }
}
```

With `deny-all` as the host baseline, add the domains the build needs:

```groovy
docker.image('maven:3.9-eclipse-temurin-17').inside(
    sandbox: [
        networkPolicy: 'deny-all',
        allowedDomains: ['repo.maven.apache.org', 'repo1.maven.org'],
        cpus: '2',
        memory: '4g'
    ]
) {
    sh 'mvn -B clean verify'
}
```

After the build, `sbx ls` should not show a leftover `jenkins-*` sandbox.

## 9. Firewall and proxy

The host must be able to reach Docker’s login, update and registry services for
`sbx` itself, for example:

* `login.docker.com`, `api.docker.com`, `hub.docker.com`
* `registry-1.docker.io`, `auth.docker.io`
* `production.cloudflare.docker.com` and the Docker image storage hosts
* `dhi.io` if you use Docker Hardened Images
* any private registry used by your templates

Sandbox egress is separate: it is governed by `sbx policy` and the pipeline’s
`networkPolicy`/`allowedDomains`, not by the host firewall alone.

If the host uses a proxy, inspect and set the daemon/sandbox proxy settings:

```bash
sbx settings ls | grep -E 'proxy|no_proxy'
# Example; see Docker's current settings documentation for values:
sbx settings set proxy http://proxy.example.com:3128
sbx settings set no_proxy '.example.com,localhost,127.0.0.1'
sbx daemon restart
```

## 10. Updates and maintenance

For the apt package:

```bash
sudo apt-get update
sudo apt-get install --only-upgrade docker-sbx
sbx version
sudo -iu jenkins
export XDG_RUNTIME_DIR=/run/user/$(id -u)
systemctl --user restart sandboxd.service
sbx diagnose
```

For the standalone package, download the newer `.deb` for your Ubuntu release
and install it with `apt-get install ./...`.

Notes:

* Keep `sbx` at 0.48.0 or newer; the plugin rejects older versions.
* `sbx daemon restart` restarts the daemon; stop/remove running sandboxes
  before a major upgrade if the release notes require it.
* Orphaned sandboxes can be listed with `sbx ls` and removed with
  `sbx rm --force <name>` or `sbx prune`.

## 11. Troubleshooting

| Symptom | Check |
|---|---|
| `sbx: command not found` in a build | The systemd unit’s `PATH` must include `/usr/bin`; or wrap the step with `withEnv(["PATH+SBX=/usr/bin"])`. |
| `permission denied` on `/dev/kvm` | `sudo usermod -aG kvm jenkins`, restart the agent, or enable nested virtualization. |
| `global network policy has not been initialized` | Run `sbx policy init balanced` or `sbx policy init deny-all` as the agent user. |
| daemon not running after reboot | `systemctl --user status sandboxd`, `loginctl enable-linger jenkins`, and `XDG_RUNTIME_DIR`. |
| authentication failures | `sbx login --password-stdin` as `jenkins`; check the PAT scope. |
| `systemctl --user` bus error | Install `dbus-user-session`, enable lingering, re-login, set `XDG_RUNTIME_DIR`. |
| workspace paths not found/visible | Run the agent on the host, not in a container; ensure the workspace and `@tmp` exist and have space. |
| image/template pull fails | `sbx login`/Docker auth for the registry; host firewall/proxy; `sbx diagnose`. |
| sandbox left behind | `sbx ls`, then `sbx rm --force <name>`; `sbx prune` for stopped ones. |
| `sbx version` too old | Install the standalone 0.51.0 `.deb` from `docker/sbx-releases`. |
| `Binary version — could not check for updates` in diagnose | An update check was blocked (observed as HTTP 403); it is non-fatal if the rest of `sbx diagnose` is healthy. |

Logs and diagnostics:

```bash
sbx diagnose
sbx daemon status --format json
tail -n 200 ~/.local/state/sandboxes/sandboxes/sandboxd/daemon.log
```

## 12. Security notes

* Run the agent and the sandbox daemon as a dedicated `jenkins` user; do not
  run the daemon as root.
* KVM access lets that user create microVMs. Limit SSH access and `sudo` for
  the account.
* Use a Docker PAT with the smallest scope that works, store it with mode 0600,
  and rotate it.
* For a shared agent, initialize the host policy to `deny-all` and make each
  pipeline declare its `allowedDomains`. Never run `sbx policy reset` from a
  build.
* The sandbox workload can read credentials passed with `sbx exec -e`; see the
  [credential notes](docker-workflow.md#credentials) before exporting secrets.
* Clean up sandboxes after failed builds; the plugin normally does this with
  `sbx rm --force`, but a Jenkins crash can leave one behind.

## References

* [Install Docker Sandboxes](https://docs.docker.com/ai/sandboxes/install/)
* [Run sandboxes in CI](https://docs.docker.com/ai/sandboxes/workflows/automation/)
* [Local network policy](https://docs.docker.com/ai/sandboxes/governance/access-controls/local/)
* [Docker Sandboxes troubleshooting](https://docs.docker.com/ai/sandboxes/troubleshooting/)
* [Standalone sbx releases](https://github.com/docker/sbx-releases/releases)
* [Docker Pipeline sandbox documentation](docker-workflow.md#docker-sandboxes)
