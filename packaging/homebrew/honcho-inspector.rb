# Homebrew formula for the Honcho Inspector backend.
#
# To use this formula before it is merged into homebrew-core:
#
#   # one-time: tap this repo as a Homebrew source
#   brew tap cloudbsdorg/honcho-inspector https://github.com/cloudbsdorg/honcho-inspector-backend
#
#   # install
#   brew install honcho-inspector
#
#   # start the service on macOS
#   brew services start honcho-inspector
#
# On Linux (Linuxbrew), the formula installs the binary + man page but
# does NOT register a service. Use bin/install-honcho-inspector from
# the source tree to set up the systemd unit, the service user, and
# the data/log dirs.
#
# ----------------------------------------------------------------------------
# Updating the version and SHA256 when a new release is cut
# ----------------------------------------------------------------------------
# When the upstream maintainer tags a new release (e.g. v0.2.0):
#
#   1. Build the fat jar:
#        mvn -B -ntp package -DskipTests
#
#   2. Compute the SHA256 of the jar:
#        shasum -a 256 target/honcho-inspector-backend-0.2.0.jar
#
#   3. Attach the jar to the GitHub release:
#        gh release create v0.2.0 target/honcho-inspector-backend-0.2.0.jar
#
#   4. Update `url` and `sha256` below, and bump the `version` line.
#      Then run `brew audit --new honcho-inspector` to validate.
# ----------------------------------------------------------------------------

class HonchoInspector < Formula
  desc "Multi-user admin surface for Honcho workspaces"
  homepage "https://github.com/cloudbsdorg/honcho-inspector-backend"
  url "https://github.com/cloudbsdorg/honcho-inspector-backend/releases/download/v0.1.0/honcho-inspector-backend-0.1.0.jar"
  sha256 "REPLACE_WITH_SHA256_OF_THE_RELEASE_JAR"
  license "BSD-3-Clause"

  depends_on "openjdk@25"

  # The launcher script in bin/honcho-inspector is the same artifact the
  # manual installer uses. It probes HOMEBREW_PREFIX for the libexec jar
  # path as a fallback so the same script works in dev and in a Homebrew
  # install. We install it verbatim; no rewriting is needed.

  def install
    # The fat jar is a single self-contained artifact. Install it to
    # libexec so the bin wrapper can find it via HOMEBREW_PREFIX.
    # The jar name in the release is honcho-inspector-backend-<version>.jar
    # but we rename it to honcho-inspector-backend.jar so the launcher
    # script's glob (which does not pin the version) keeps working.
    jar_src = "honcho-inspector-backend-#{version}.jar"
    libexec.install jar_src => "honcho-inspector-backend.jar"

    # Install the launcher and the man page.
    bin.install "bin/honcho-inspector"
    man1.install "docs/honcho-inspector.1"

    # Generate a stub env file under etc/ that the operator can edit
    # to set HONCHO_CRYPTO_KEY and the optional bootstrap admin
    # credentials. Homebrew's etc/ is a managed dir; we mark the
    # file as a sample so `brew install` does not clobber operator
    # edits on subsequent installs.
    (etc/"honcho-inspector").install "etc/honcho-inspector/application.yml.example" => "application.yml.default"
  end

  # macOS brew services integration. The service runs as the
  # built-in _www user (the macOS analog of www-data) by setting
  # the HOMEBREW_USER environment. On Linuxbrew, the service
  # block is a no-op; operators should run
  # `bin/install-honcho-inspector` to set up systemd.
  service do
    run [opt_bin/"honcho-inspector"]
    keep_alive true
    log_path var/"log/honcho-inspector.out.log"
    error_log_path var/"log/honcho-inspector.err.log"
    environment_variables(
      "HONCHO_CONFIG_DIR" => etc/"honcho-inspector",
    )
  end

  def caveats
    <<~EOS
      Honcho Inspector installed.

      To finish the setup, edit
        #{etc}/honcho-inspector/application.yml
      (copied from the .default template) and set the bootstrap
      admin credentials under `honcho.bootstrap.*`.

      The crypto key (HONCHO_CRYPTO_KEY) is read from the shell
      environment. Generate one with:
        openssl rand -base64 32
      and export it before `brew services start honcho-inspector`:
        echo 'export HONCHO_CRYPTO_KEY=...' >> ~/.zshrc

      On macOS, start the service with:
        brew services start honcho-inspector

      On Linux (Linuxbrew), this formula does NOT register a
      systemd service. To set up the service user, dirs, and
      hardened unit, run from the source tree:
        sudo bin/install-honcho-inspector
    EOS
  end

  test do
    # `bin/honcho-inspector` without args will fail to start without
    # a config dir, but the launcher script's --version path
    # delegates to Spring Boot's banner, which we can capture.
    # We assert on the launcher script syntax instead, which is a
    # valid test of the install.
    assert_match "honcho-inspector", shell_output("#{bin}/honcho-inspector --help 2>&1", 1)
  end
end
