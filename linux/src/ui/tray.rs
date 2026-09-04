//! System tray icon (StatusNotifierItem).
//!
//! The `ksni` crate provides the SNI + dbusmenu protocol. Icon/menu policy
//! stays in the pure [`crate::ui::tray_model`] module. Branded per-state artwork from
//! `assets/tray/` is sent as a raw ARGB `IconPixmap` (not a theme `IconName`),
//! so it renders on every host and when running from source.

use std::path::PathBuf;
use std::sync::Arc;

use ksni::menu::{MenuItem as KsniMenuItem, StandardItem};
use ksni::{Category, Icon, Status, Tray};

use super::tray_model::{self, MenuKind};

/// Locate the bundled tray artwork directory.
pub fn tray_assets_dir() -> Option<PathBuf> {
    let mut candidates = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            candidates.push(dir.join("assets/tray"));
            candidates.push(dir.join("../share/meeting-recorder/tray"));
        }
    }
    candidates.push(PathBuf::from("/usr/share/meeting-recorder/tray"));
    if let Some(home) = dirs::home_dir() {
        candidates.push(home.join(".local/share/meeting-recorder/tray"));
    }
    // Source-checkout fallback (dev).
    candidates.push(PathBuf::from("linux/assets/tray"));
    candidates.push(PathBuf::from("assets/tray"));
    candidates.into_iter().find(|p| p.is_dir())
}

fn load_png_rgba(path: &PathBuf) -> Option<(u32, u32, Vec<u8>)> {
    let file = std::fs::File::open(path).ok()?;
    let bytes = {
        let mut buf = Vec::new();
        use std::io::Read as _;
        let mut f = file;
        f.read_to_end(&mut buf).ok()?;
        buf
    };
    decode_png_rgba(&bytes)
}

fn decode_png_rgba(bytes: &[u8]) -> Option<(u32, u32, Vec<u8>)> {
    let decoder = png::Decoder::new(bytes);
    let mut reader = decoder.read_info().ok()?;
    let mut buf = vec![0u8; reader.output_buffer_size()];
    let info = reader.next_frame(&mut buf).ok()?;
    let (w, h) = (info.width, info.height);
    use png::ColorType::*;
    let rgba: Vec<u8> = match info.color_type {
        Rgba => buf[..info.buffer_size()].to_vec(),
        Rgb => buf[..info.buffer_size()]
            .chunks(3)
            .flat_map(|p| [p[0], p[1], p[2], 255])
            .collect(),
        Grayscale => buf[..info.buffer_size()]
            .iter()
            .flat_map(|&g| [g, g, g, 255])
            .collect(),
        GrayscaleAlpha => buf[..info.buffer_size()]
            .chunks(2)
            .flat_map(|p| [p[0], p[0], p[0], p[1]])
            .collect(),
        _ => return None,
    };
    // ksni wants ARGB32 byte order.
    let argb: Vec<u8> = rgba
        .chunks(4)
        .flat_map(|p| [p[3], p[0], p[1], p[2]])
        .collect();
    Some((w, h, argb))
}

/// Shared mutable tray state, updated from the daemon loop via [`update_tray`].
pub struct AppTray {
    state: String,
    processing: Vec<(i64, String)>,
    pixmaps: Vec<Icon>,
    on_command: Arc<dyn Fn(String) + Send + Sync>,
}

/// Embedded 48px fallbacks so the tray never renders empty when the artwork
/// directory is missing (standalone binary installs, bad packaging, dev runs
/// from another cwd). Filesystem art still wins when present (crisper sizes).
fn embedded_pixmap(base: &str) -> Option<(u32, u32, Vec<u8>)> {
    let bytes: &[u8] = match base {
        "meeting-recorder" => include_bytes!("../../assets/tray/meeting-recorder-48.png"),
        "meeting-recorder-recording" => {
            include_bytes!("../../assets/tray/meeting-recorder-recording-48.png")
        }
        "meeting-recorder-paused" => {
            include_bytes!("../../assets/tray/meeting-recorder-paused-48.png")
        }
        "meeting-recorder-processing" => {
            include_bytes!("../../assets/tray/meeting-recorder-processing-48.png")
        }
        _ => return None,
    };
    decode_png_rgba(bytes)
}

impl AppTray {
    pub fn new(on_command: Arc<dyn Fn(String) + Send + Sync>) -> Self {
        let mut t = Self {
            state: "idle".into(),
            processing: Vec::new(),
            pixmaps: Vec::new(),
            on_command,
        };
        t.reload_pixmap();
        t
    }

    fn reload_pixmap(&mut self) {
        self.pixmaps.clear();
        let base = tray_model::icon_for_state(&self.state, self.processing.len());
        if let Some(dir) = tray_assets_dir() {
            // Prefer the largest bundled size for crisp rendering.
            for size in [64, 48, 32, 24] {
                let path = dir.join(format!("{base}-{size}.png"));
                if let Some((w, h, argb)) = load_png_rgba(&path) {
                    self.pixmaps.push(Icon {
                        width: w as i32,
                        height: h as i32,
                        data: argb,
                    });
                    break;
                }
            }
        }
        if self.pixmaps.is_empty() {
            if let Some((w, h, argb)) = embedded_pixmap(base) {
                self.pixmaps.push(Icon {
                    width: w as i32,
                    height: h as i32,
                    data: argb,
                });
            }
        }
    }

    fn fire(&self, cmd: String) {
        (self.on_command)(cmd);
    }
}

impl Tray for AppTray {
    fn id(&self) -> String {
        "meeting-recorder".into()
    }

    fn title(&self) -> String {
        "Meeting Recorder".into()
    }

    fn category(&self) -> Category {
        Category::ApplicationStatus
    }

    fn status(&self) -> Status {
        if self.state == "recording" {
            Status::NeedsAttention
        } else {
            Status::Active
        }
    }

    fn icon_pixmap(&self) -> Vec<Icon> {
        self.pixmaps.clone()
    }

    fn icon_name(&self) -> String {
        // Theme fallback for hosts that ignore pixmaps: the hicolor theme
        // ships `meeting-recorder`, so idle always has something to render.
        // Per-state artwork is pixmap-only (no theme equivalents exist).
        "meeting-recorder".into()
    }

    fn tool_tip(&self) -> ksni::ToolTip {
        ksni::ToolTip {
            title: "Meeting Recorder".into(),
            description: match self.state.as_str() {
                "recording" => "Recording…".into(),
                "paused" => "Paused".into(),
                _ if !self.processing.is_empty() => {
                    format!("Processing ({})…", self.processing.len())
                }
                _ => "Ready to record".into(),
            },
            ..Default::default()
        }
    }

    fn activate(&mut self, _x: i32, _y: i32) {
        // Left-click focuses the window where the host delivers Activate
        // (e.g. KDE Plasma); the GNOME AppIndicator extension opens the menu
        // instead.
        self.fire(crate::core::commands::SHOW_WINDOW.to_string());
    }

    fn menu(&self) -> Vec<KsniMenuItem<Self>> {
        tray_model::build_menu_model(&self.state, &self.processing)
            .into_iter()
            .map(|item| match item.kind {
                MenuKind::Separator => KsniMenuItem::Separator,
                MenuKind::Label => StandardItem {
                    label: item.label,
                    enabled: false,
                    ..Default::default()
                }
                .into(),
                MenuKind::Action => {
                    let cmd = item.command.clone();
                    let fire_cmd = cmd.clone();
                    StandardItem {
                        label: item.label,
                        enabled: item.enabled,
                        activate: Box::new(move |tray: &mut Self| {
                            tray.fire(fire_cmd.clone());
                        }),
                        ..Default::default()
                    }
                    .into()
                }
            })
            .collect()
    }
}

/// Refresh the tray from engine state. Call after every engine mutation.
pub async fn update_tray(
    handle: &ksni::Handle<AppTray>,
    state: String,
    processing: Vec<(i64, String)>,
) {
    handle
        .update(|tray: &mut AppTray| {
            tray.state = state;
            tray.processing = processing;
            tray.reload_pixmap();
        })
        .await;
}

/// Decode helper re-export for tests.
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bundled_artwork_decodes() {
        let dir = PathBuf::from(concat!(env!("CARGO_MANIFEST_DIR"), "/assets/tray"));
        if !dir.is_dir() {
            return; // running installed — nothing to check
        }
        for name in [
            "meeting-recorder-48.png",
            "meeting-recorder-recording-48.png",
        ] {
            let (w, h, argb) =
                load_png_rgba(&dir.join(name)).unwrap_or_else(|| panic!("decode {name}"));
            assert_eq!((w, h), (48, 48));
            assert_eq!(argb.len(), 48 * 48 * 4);
            // Sanity: alpha channel is not uniformly zero.
            assert!(argb.chunks(4).any(|p| p[0] != 0));
        }
    }

    #[test]
    fn embedded_fallback_decodes_for_all_states() {
        for base in [
            "meeting-recorder",
            "meeting-recorder-recording",
            "meeting-recorder-paused",
            "meeting-recorder-processing",
        ] {
            let (w, h, argb) =
                embedded_pixmap(base).unwrap_or_else(|| panic!("embedded decode {base}"));
            assert_eq!((w, h), (48, 48));
            assert_eq!(argb.len(), 48 * 48 * 4);
            assert!(argb.chunks(4).any(|p| p[0] != 0));
        }
    }

    #[test]
    fn tray_always_has_icon() {
        let tray = AppTray::new(Arc::new(|_| {}));
        assert!(
            !tray.icon_pixmap().is_empty(),
            "tray must always carry a pixmap (embedded fallback)"
        );
        assert_eq!(tray.icon_name(), "meeting-recorder");
    }
}
