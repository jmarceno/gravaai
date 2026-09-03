#!/usr/bin/env bash
# install.sh — Install Meeting Recorder on Arch Linux (user-local, no compiler).
#
# The app binary is fetched as a prebuilt release asset — installing never
# builds anything, so no Rust toolchain, base-devel, or any other compiler is
# required on this machine. Developers who want to build from source: see
# README.md ("Building from source").

set -euo pipefail

APP_NAME="meeting-recorder"
# The desktop file is named after the application id so the GNOME/Wayland
# shell (and Dash to Panel) can map a running window back to it and show the app
# icon instead of a generic one.
APP_ID="io.github.jmarceno.Gravaai"
REPO="jmarceno/gravaai"
# Release version to install, e.g. MEETING_RECORDER_VERSION=1.2.3 ./install.sh.
# Defaults to the latest published release.
APP_VERSION="${MEETING_RECORDER_VERSION:-latest}"
INSTALL_DIR="$HOME/.local/share/$APP_NAME"
BIN_DIR="$HOME/.local/bin"
APPS_DIR="$HOME/.local/share/applications"
ICON_THEME_DIR="$HOME/.local/share/icons/hicolor"
BIN_PATH="$BIN_DIR/$APP_NAME"
DESKTOP="$APPS_DIR/$APP_ID.desktop"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Colors ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; NC='\033[0m'
info()    { echo -e "${GREEN}[info]${NC} $*"; }
warn()    { echo -e "${YELLOW}[warn]${NC} $*"; }
err()     { echo -e "${RED}[error]${NC} $*" >&2; }

# ── 1. System dependencies (Arch only, binary packages — no compiler) ─────────
install_deps_pacman() {
    info "Installing system dependencies (pacman)..."
    sudo pacman -Syu --noconfirm \
        gtk4 libadwaita libnotify libpulse pipewire-pulse \
        ffmpeg curl tar
}

if command -v pacman &>/dev/null; then
    install_deps_pacman
else
    err "Arch Linux (pacman) is required. This project supports Arch only."
    exit 1
fi

# The app exposes its tray as a StatusNotifierItem (SNI) over D-Bus. GNOME has no
# built-in SNI host, so the AppIndicator/KStatusNotifierItem extension is needed
# to make the tray icon appear (it provides the SNI host, not the old library).
install_gnome_extensions() {
    if [[ "${XDG_CURRENT_DESKTOP:-}" == *GNOME* ]]; then
        info "GNOME detected. Installing AppIndicator/KStatusNotifierItem extension (SNI host)..."
        sudo pacman -S --noconfirm gnome-shell-extension-appindicator
        warn "Please enable the 'AppIndicator and KStatusNotifierItem Support' extension in the GNOME Extensions app, and then log out and log back in."
    fi
}

install_gnome_extensions

# ── 2. Prebuilt binary (no source builds) ─────────────────────────────────────
arch_suffix() {
    case "$(uname -m)" in
        x86_64)  echo "x86_64" ;;
        aarch64) echo "aarch64" ;;
        *)
            err "Unsupported architecture: $(uname -m) (x86_64 and aarch64 only)."
            exit 1
            ;;
    esac
}

resolve_version() {
    if [[ "$APP_VERSION" != "latest" ]]; then
        echo "v${APP_VERSION#v}"
        return
    fi
    curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
        | grep -m1 '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/'
}

VERSION="$(resolve_version)"
if [[ -z "${VERSION:-}" ]]; then
    err "Could not determine the latest release (network issue?)."
    err "Set MEETING_RECORDER_VERSION explicitly, e.g.:"
    err "  MEETING_RECORDER_VERSION=1.2.3 linux/install.sh"
    exit 1
fi

ASSET="$APP_NAME-$VERSION-$(arch_suffix)"
URL="https://github.com/$REPO/releases/download/$VERSION/$ASSET"
TMP_BIN="$(mktemp)"
info "Downloading prebuilt $APP_NAME $VERSION ($(arch_suffix))…"
if ! curl -fsSL -o "$TMP_BIN" "$URL"; then
    err "Download failed: $URL"
    err "Check that release $VERSION publishes a $(arch_suffix) binary,"
    err "or install the pacman package instead (see README.md)."
    rm -f "$TMP_BIN"
    exit 1
fi

# ── 3. Install binary + assets ────────────────────────────────────────────────
info "Installing binary to $BIN_PATH…"
mkdir -p "$BIN_DIR" "$INSTALL_DIR"
install -m 755 "$TMP_BIN" "$BIN_PATH"
rm -f "$TMP_BIN"

info "Installing tray artwork and icons…"
mkdir -p "$INSTALL_DIR/tray" "$INSTALL_DIR/icons"
cp -r "$SCRIPT_DIR/assets/tray/." "$INSTALL_DIR/tray/"
cp -r "$SCRIPT_DIR/assets/icons/." "$INSTALL_DIR/icons/"
for size in 16 24 32 48 64 128 256; do
    mkdir -p "$ICON_THEME_DIR/${size}x${size}/apps"
    install -m 644 "$SCRIPT_DIR/assets/icons/hicolor/${size}x${size}/apps/$APP_NAME.png" \
        "$ICON_THEME_DIR/${size}x${size}/apps/$APP_NAME.png"
done
mkdir -p "$ICON_THEME_DIR/scalable/apps"
install -m 644 "$SCRIPT_DIR/assets/icons/hicolor/scalable/apps/$APP_NAME.svg" \
    "$ICON_THEME_DIR/scalable/apps/$APP_NAME.svg"
gtk-update-icon-cache -f -t "$ICON_THEME_DIR" 2>/dev/null || true

# ── 4. System log directory ───────────────────────────────────────────────────
SYSTEM_LOG_DIR="/var/log/meeting-recorder"
info "Creating system log directory at $SYSTEM_LOG_DIR…"
sudo mkdir -p "$SYSTEM_LOG_DIR"
sudo chown "$USER:$USER" "$SYSTEM_LOG_DIR"
sudo chmod 755 "$SYSTEM_LOG_DIR"

# ── 5. Desktop entry ─────────────────────────────────────────────────────────
mkdir -p "$APPS_DIR"
rm -f "$APPS_DIR/$APP_NAME.desktop"
sed "s|LAUNCHER_PATH|$BIN_PATH|g" "$SCRIPT_DIR/meeting-recorder.desktop.template" \
    > "$DESKTOP"
chmod +x "$DESKTOP"
info "Desktop entry created at $DESKTOP"

# Update desktop database if available
update-desktop-database "$APPS_DIR" 2>/dev/null || true

echo
info "Install complete ($VERSION). Launch with: $APP_NAME"
info "Configure an OpenAI-compatible endpoint in Settings (gear icon → Preferences),"
info "or install local engines there — everything arrives prebuilt, no compiler needed."
