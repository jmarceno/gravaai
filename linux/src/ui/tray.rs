//! System tray icon (StatusNotifierItem).
//!
//! The `ksni` crate provides the SNI + dbusmenu protocol. Icon/menu policy
//! stays in the pure [`crate::ui::tray_model`] module. A single branded logo
//! from `assets/tray/` is sent as a raw ARGB `IconPixmap`; recording / paused /
//! processing states are composed at runtime via [`crate::ui::tray_icon`].

use std::path::PathBuf;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use ksni::menu::{MenuItem as KsniMenuItem, StandardItem};
use ksni::{Category, Icon, Status, Tray};

use crate::config::defaults::{APP_DIR_NAME, APP_NAME};

use super::tray_icon::{
    compose, needs_animation, phase_from_secs, TrayAppearance, BREATHE_PERIOD_SECS,
    SWEEP_PERIOD_SECS,
};
use super::tray_model::{self, MenuKind};

/// Locate the bundled tray artwork directory.
pub fn tray_assets_dir() -> Option<PathBuf> {
    let mut candidates = Vec::new();
    if let Ok(appdir) = std::env::var("APPDIR") {
        // Only trust APPDIR when our exe lives under it (ignore host AppImages).
        if crate::utils::exe::own_appimage().is_some() {
            candidates.push(PathBuf::from(&appdir).join(format!("usr/share/{APP_DIR_NAME}/tray")));
        }
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            candidates.push(dir.join("assets/tray"));
            candidates.push(dir.join(format!("../share/{APP_DIR_NAME}/tray")));
        }
    }
    candidates.push(PathBuf::from(format!("/usr/share/{APP_DIR_NAME}/tray")));
    if let Some(home) = dirs::home_dir() {
        candidates.push(home.join(format!(".local/share/{APP_DIR_NAME}/tray")));
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
    base_w: u32,
    base_h: u32,
    base_argb: Vec<u8>,
    pixmaps: Vec<Icon>,
    on_command: Arc<dyn Fn(String) + Send + Sync>,
}

/// Embedded 48px fallback so the tray never renders empty when the artwork
/// directory is missing (standalone binary installs, bad packaging, dev runs
/// from another cwd). Filesystem art still wins when present (crisper sizes).
fn embedded_base() -> Option<(u32, u32, Vec<u8>)> {
    decode_png_rgba(include_bytes!("../../assets/tray/gravaai-48.png"))
}

fn load_base_pixmap() -> (u32, u32, Vec<u8>) {
    if let Some(dir) = tray_assets_dir() {
        for size in [64, 48] {
            let path = dir.join(format!("{APP_DIR_NAME}-{size}.png"));
            if let Some(decoded) = load_png_rgba(&path) {
                return decoded;
            }
        }
    }
    embedded_base().expect("embedded tray icon must decode")
}

fn now_secs() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0)
}

impl AppTray {
    pub fn new(on_command: Arc<dyn Fn(String) + Send + Sync>) -> Self {
        let (base_w, base_h, base_argb) = load_base_pixmap();
        let mut t = Self {
            state: "idle".into(),
            processing: Vec::new(),
            base_w,
            base_h,
            base_argb,
            pixmaps: Vec::new(),
            on_command,
        };
        t.recompose(0.0);
        t
    }

    fn appearance(&self) -> TrayAppearance {
        tray_model::appearance_for_state(&self.state, self.processing.len())
    }

    fn phase_for(&self, appearance: TrayAppearance) -> f32 {
        let secs = now_secs();
        match appearance {
            TrayAppearance::Recording => phase_from_secs(secs, BREATHE_PERIOD_SECS),
            TrayAppearance::Processing => phase_from_secs(secs, SWEEP_PERIOD_SECS),
            _ => 0.0,
        }
    }

    fn recompose(&mut self, phase: f32) {
        let appearance = self.appearance();
        let argb = compose(appearance, self.base_w, self.base_h, &self.base_argb, phase);
        self.pixmaps = vec![Icon {
            width: self.base_w as i32,
            height: self.base_h as i32,
            data: argb,
        }];
    }

    /// Advance an animated appearance (recording breathe / processing sweep).
    /// Returns true when the pixmap was updated.
    pub fn tick_animation(&mut self) -> bool {
        let appearance = self.appearance();
        if !needs_animation(appearance) {
            return false;
        }
        let phase = self.phase_for(appearance);
        self.recompose(phase);
        true
    }

    fn fire(&self, cmd: String) {
        (self.on_command)(cmd);
    }
}

impl Tray for AppTray {
    fn id(&self) -> String {
        APP_DIR_NAME.into()
    }

    fn title(&self) -> String {
        APP_NAME.into()
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
        // ships `gravaai`, so idle always has something to render.
        // Animated states are pixmap-only.
        APP_DIR_NAME.into()
    }

    fn tool_tip(&self) -> ksni::ToolTip {
        ksni::ToolTip {
            title: APP_NAME.into(),
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
                    let fire_cmd = item.command.clone();
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
            let appearance = tray.appearance();
            let phase = tray.phase_for(appearance);
            tray.recompose(phase);
        })
        .await;
}

/// Advance tray animation frames without touching D-Bus snapshots.
pub async fn tick_tray_animation(handle: &ksni::Handle<AppTray>) {
    handle
        .update(|tray: &mut AppTray| {
            let _ = tray.tick_animation();
        })
        .await;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bundled_artwork_decodes() {
        let dir = PathBuf::from(concat!(env!("CARGO_MANIFEST_DIR"), "/assets/tray"));
        if !dir.is_dir() {
            return; // running installed — nothing to check
        }
        for name in ["gravaai-48.png", "gravaai-64.png"] {
            let (w, h, argb) =
                load_png_rgba(&dir.join(name)).unwrap_or_else(|| panic!("decode {name}"));
            assert!(w >= 48 && h >= 48);
            assert_eq!(argb.len(), (w * h * 4) as usize);
            assert!(argb.chunks(4).any(|p| p[0] != 0));
        }
    }

    #[test]
    fn embedded_fallback_decodes() {
        let (w, h, argb) = embedded_base().expect("embedded decode");
        assert_eq!((w, h), (48, 48));
        assert_eq!(argb.len(), 48 * 48 * 4);
        assert!(argb.chunks(4).any(|p| p[0] != 0));
    }

    #[test]
    fn tray_always_has_icon() {
        let tray = AppTray::new(Arc::new(|_| {}));
        assert!(
            !tray.icon_pixmap().is_empty(),
            "tray must always carry a pixmap (embedded fallback)"
        );
        assert_eq!(tray.icon_name(), APP_DIR_NAME);
        assert_eq!(tray.id(), APP_DIR_NAME);
        assert_eq!(tray.title(), APP_NAME);
    }

    #[test]
    fn compose_effects_on_real_icon() {
        let (w, h, base) = embedded_base().unwrap();
        let idle = compose(TrayAppearance::Idle, w, h, &base, 0.0);
        assert_eq!(idle, base);
        let rec = compose(TrayAppearance::Recording, w, h, &base, 0.75);
        // Mean alpha of recording trough should be lower than idle.
        let mean = |buf: &[u8]| {
            let mut s = 0u64;
            let mut n = 0u64;
            for px in buf.chunks(4) {
                if px[0] > 0 {
                    s += px[0] as u64;
                    n += 1;
                }
            }
            s as f64 / n as f64
        };
        assert!(mean(&rec) < mean(&idle));

        let paused = compose(TrayAppearance::Paused, w, h, &base, 0.0);
        // Opaque non-bar pixels should be chroma-free (equal RGB).
        let mut found_gray = false;
        for px in paused.chunks(4) {
            if px[0] > 40 && px[1] == px[2] && px[2] == px[3] && px[1] < 230 {
                found_gray = true;
                break;
            }
        }
        assert!(found_gray, "paused icon should contain grayscale pixels");

        let early = compose(TrayAppearance::Processing, w, h, &base, 0.1);
        let late = compose(TrayAppearance::Processing, w, h, &base, 0.8);
        assert_ne!(early, late);
    }

    #[test]
    fn tick_animation_only_when_needed() {
        let mut tray = AppTray::new(Arc::new(|_| {}));
        assert!(!tray.tick_animation()); // idle
        tray.state = "recording".into();
        assert!(tray.tick_animation());
        tray.state = "paused".into();
        assert!(!tray.tick_animation());
        tray.state = "idle".into();
        tray.processing = vec![(1, "x".into())];
        assert!(tray.tick_animation());
    }
}
