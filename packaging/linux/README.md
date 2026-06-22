# Linux packaging notes

The Honcho Inspector backend is a single self-contained Spring Boot
fat jar. There is no native compilation step and no per-distro
dependency beyond Java 25. The recommended install paths on Linux
are, in order of operator preference:

## 1. Homebrew / Linuxbrew (recommended for cross-platform consistency)

Same formula as macOS:

```bash
brew tap cloudbsdorg/honcho-inspector https://github.com/cloudbsdorg/honcho-inspector-backend
brew install honcho-inspector
```

The formula installs the jar to `libexec/`, the launcher to `bin/`,
and the man page to `share/man/man1/`. It does NOT register a
systemd service on Linuxbrew. To set up the service user, dirs,
and hardened unit, run from the source tree:

```bash
sudo bin/install-honcho-inspector
```

That script detects Linux, creates the `www-data` system user,
installs `/etc/systemd/system/honcho-inspector.service`, and
enables the service.

## 2. POSIX install script (the project's own installer)

```bash
mvn -B -ntp package -DskipTests
sudo bin/install-honcho-inspector
```

Same script as the Homebrew tap path; the install copies the jar,
the systemd unit, and the man page to the right places. See
[`bin/install-honcho-inspector`](../bin/install-honcho-inspector)
for the full procedure.

## 3. .deb and .rpm (out of scope for this project)

The project does not currently ship native `.deb` or `.rpm`
packages. The two options above cover the most common
operator preferences. If you need a native package, the source
for both is straightforward:

- **.deb**: use `fpm` (`gem install fpm`) with the install
  script as the post-install hook:
  ```bash
  fpm -s dir -t deb -n honcho-inspector -v 0.1.0 \
      --deb-systemd etc/systemd/honcho-inspector.service \
      --after-install bin/install-honcho-inspector \
      target/honcho-inspector-backend-0.1.0.jar=/usr/local/lib/honcho-inspector/ \
      bin/honcho-inspector=/usr/local/bin/ \
      docs/honcho-inspector.1=/usr/local/share/man/man1/
  ```
- **.rpm**: same idea with `rpmbuild` or `fpm -s dir -t rpm`.

Both should mark the package as depending on `java-25-openjdk`
(or `java-25-openjdk-headless` for the headless variant) and as
conflicting with any older `honcho-inspector` install.

## Hardening checklist (Linux-specific)

After install, verify:

- [ ] Service is running as `www-data` (not root):
  `ps -o user,group,comm -p $(pgrep -f honcho-inspector-backend)`
- [ ] Database dir is owned by `www-data:www-data`:
  `ls -la /var/lib/honcho-inspector`
- [ ] Config dir is owned by `root:www-data`:
  `ls -la /etc/honcho-inspector`
- [ ] Env file is `0640 root:www-data`:
  `ls -la /etc/default/honcho-inspector`
- [ ] Unit file is hardened:
  `systemctl cat honcho-inspector | grep -E 'NoNewPrivileges|ProtectSystem|PrivateTmp'`

See [`../docs/SECURITY.md` §5](../docs/SECURITY.md) for the
full operator hardening checklist.
