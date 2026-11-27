#!/usr/bin/env sh
# Small project bootstrapper for environments without a preinstalled Gradle.
# Keep GRADLE_VERSION in sync with gradle/wrapper/gradle-wrapper.properties.
set -eu

GRADLE_VERSION="9.6.0"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
INSTALL_DIR="$GRADLE_USER_HOME/wrapper/dists/local-intercom/gradle-$GRADLE_VERSION"
GRADLE_HOME="$INSTALL_DIR/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
    mkdir -p "$INSTALL_DIR"
    ARCHIVE="$INSTALL_DIR/gradle-$GRADLE_VERSION-bin.zip"
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --silent --show-error \
            "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" \
            --output "$ARCHIVE"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -O "$ARCHIVE"
    else
        echo "curl or wget is required to download Gradle." >&2
        exit 1
    fi
    unzip -q "$ARCHIVE" -d "$INSTALL_DIR"
    rm -f "$ARCHIVE"
fi

exec "$GRADLE_BIN" "$@"
