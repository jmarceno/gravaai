//! Pure tray pixmap composition from a single base icon.
//!
//! ksni only accepts static ARGB pixmaps, so recording / paused / processing
//! visuals are produced here as pixel transforms of one idle logo.

use std::f32::consts::TAU;

/// Tray visual driven by recording state + job activity.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrayAppearance {
    Idle,
    Recording,
    Paused,
    Processing,
}

/// Whether the appearance needs a periodic recompose (breathing / sweep).
pub fn needs_animation(appearance: TrayAppearance) -> bool {
    matches!(
        appearance,
        TrayAppearance::Recording | TrayAppearance::Processing
    )
}

/// Breathing period for the recording pulse (seconds).
pub const BREATHE_PERIOD_SECS: f32 = 2.5;
/// Processing sweep period (seconds).
pub const SWEEP_PERIOD_SECS: f32 = 1.6;

/// Map wall-clock seconds into a 0..1 phase for the given period.
pub fn phase_from_secs(secs: f64, period: f32) -> f32 {
    let p = period.max(0.001) as f64;
    ((secs / p) % 1.0) as f32
}

/// Compose an ARGB32 pixmap (`[A,R,G,B]` per pixel, row-major) from a base.
pub fn compose(
    appearance: TrayAppearance,
    width: u32,
    height: u32,
    base_argb: &[u8],
    phase: f32,
) -> Vec<u8> {
    let expected = (width as usize) * (height as usize) * 4;
    assert_eq!(base_argb.len(), expected, "base pixmap size mismatch");
    let phase = phase.rem_euclid(1.0);
    match appearance {
        TrayAppearance::Idle => base_argb.to_vec(),
        TrayAppearance::Recording => breathe(base_argb, phase),
        TrayAppearance::Paused => paused(width, height, base_argb),
        TrayAppearance::Processing => processing_sweep(width, height, base_argb, phase),
    }
}

fn breathe(base: &[u8], phase: f32) -> Vec<u8> {
    // Smooth sine pulse: ~55% → 100% opacity.
    let t = 0.5 + 0.5 * (phase * TAU).sin();
    let factor = 0.55 + 0.45 * t;
    let mut out = base.to_vec();
    for px in out.chunks_exact_mut(4) {
        let a = px[0] as f32 * factor;
        px[0] = a.round().clamp(0.0, 255.0) as u8;
    }
    out
}

fn to_gray(r: u8, g: u8, b: u8) -> u8 {
    // Rec. 601 luma.
    ((r as u16 * 77 + g as u16 * 150 + b as u16 * 29) / 256) as u8
}

fn paused(width: u32, height: u32, base: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(base.len());
    for px in base.chunks_exact(4) {
        let (a, r, g, b) = (px[0], px[1], px[2], px[3]);
        let y = to_gray(r, g, b);
        out.extend_from_slice(&[a, y, y, y]);
    }
    // Two vertical pause bars centered on the glyph.
    let bar_w = (width as f32 * 0.10).round().max(2.0) as i32;
    let gap = (width as f32 * 0.08).round().max(2.0) as i32;
    let bar_h = (height as f32 * 0.36).round().max(6.0) as i32;
    let cx = width as i32 / 2;
    let cy = height as i32 / 2;
    let top = cy - bar_h / 2;
    let left0 = cx - gap / 2 - bar_w;
    let left1 = cx + gap / 2;
    draw_bar(
        &mut out,
        width,
        height,
        (left0, top, bar_w, bar_h),
        [255, 240, 240, 240],
    );
    draw_bar(
        &mut out,
        width,
        height,
        (left1, top, bar_w, bar_h),
        [255, 240, 240, 240],
    );
    out
}

fn draw_bar(buf: &mut [u8], width: u32, height: u32, rect: (i32, i32, i32, i32), argb: [u8; 4]) {
    let (x0, y0, bw, bh) = rect;
    let [a, r, g, b] = argb;
    for dy in 0..bh {
        let y = y0 + dy;
        if y < 0 || y >= height as i32 {
            continue;
        }
        for dx in 0..bw {
            let x = x0 + dx;
            if x < 0 || x >= width as i32 {
                continue;
            }
            let i = ((y as u32 * width + x as u32) * 4) as usize;
            buf[i] = a;
            buf[i + 1] = r;
            buf[i + 2] = g;
            buf[i + 3] = b;
        }
    }
}

fn processing_sweep(width: u32, height: u32, base: &[u8], phase: f32) -> Vec<u8> {
    let mut out = base.to_vec();
    let w = width as f32;
    let band = (w * 0.22).max(4.0);
    // Soft highlight center travels left → right, wrapping.
    let center = phase * (w + band) - band * 0.5;
    for y in 0..height {
        for x in 0..width {
            let i = ((y * width + x) * 4) as usize;
            let a = out[i];
            if a == 0 {
                continue;
            }
            let dist = ((x as f32) - center).abs();
            if dist >= band {
                continue;
            }
            // Cosine falloff → brighten toward white.
            let t = (1.0 - dist / band) * 0.5 * (1.0 + (dist / band * std::f32::consts::PI).cos());
            let boost = (t * 0.85).clamp(0.0, 1.0);
            for c in 1..4 {
                let v = out[i + c] as f32;
                out[i + c] = (v + (255.0 - v) * boost).round().clamp(0.0, 255.0) as u8;
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn solid(w: u32, h: u32, a: u8, r: u8, g: u8, b: u8) -> Vec<u8> {
        let mut v = Vec::with_capacity((w * h * 4) as usize);
        for _ in 0..(w * h) {
            v.extend_from_slice(&[a, r, g, b]);
        }
        v
    }

    #[test]
    fn idle_is_identity() {
        let base = solid(4, 4, 255, 10, 20, 30);
        let out = compose(TrayAppearance::Idle, 4, 4, &base, 0.0);
        assert_eq!(out, base);
    }

    #[test]
    fn recording_modulates_alpha() {
        let base = solid(4, 4, 200, 10, 48, 88);
        let dim = compose(TrayAppearance::Recording, 4, 4, &base, 0.75); // near trough of sin
        let bright = compose(TrayAppearance::Recording, 4, 4, &base, 0.25); // near peak
                                                                            // phase 0.25 → sin(π/2)=1 → factor 1.0; phase 0.75 → sin(3π/2)=-1 → factor 0.55
        assert_eq!(bright[0], 200);
        assert!(dim[0] < bright[0]);
        assert!(dim[0] >= 100); // ~110
    }

    #[test]
    fn paused_is_grayscale_with_bars() {
        let base = solid(32, 32, 255, 0, 176, 152); // teal
        let out = compose(TrayAppearance::Paused, 32, 32, &base, 0.0);
        // Corner pixel should be gray (equal RGB), not teal.
        assert_eq!(out[1], out[2]);
        assert_eq!(out[2], out[3]);
        assert_ne!(out[1], 0); // not pure black
                               // Center should include bright pause bars.
        let cx = 16usize;
        let cy = 16usize;
        let i = (cy * 32 + cx) * 4;
        // Midpoint between bars may still be gray; sample a bar column.
        let bar_i = (cy * 32 + (cx - 4)) * 4;
        assert!(out[bar_i + 1] >= 200, "pause bar should be light");
        let _ = i;
    }

    #[test]
    fn processing_brightest_column_tracks_phase() {
        let base = solid(40, 10, 255, 40, 40, 40);
        let early = compose(TrayAppearance::Processing, 40, 10, &base, 0.15);
        let late = compose(TrayAppearance::Processing, 40, 10, &base, 0.75);
        let col_brightness = |buf: &[u8], x: u32| {
            let i = (x * 4) as usize; // row 0
            buf[i + 1] as u32 + buf[i + 2] as u32 + buf[i + 3] as u32
        };
        let mut early_best = 0u32;
        let mut early_x = 0u32;
        let mut late_best = 0u32;
        let mut late_x = 0u32;
        for x in 0..40 {
            let e = col_brightness(&early, x);
            if e > early_best {
                early_best = e;
                early_x = x;
            }
            let l = col_brightness(&late, x);
            if l > late_best {
                late_best = l;
                late_x = x;
            }
        }
        assert!(
            early_x < late_x,
            "sweep should move rightward ({early_x} → {late_x})"
        );
    }

    #[test]
    fn phase_wraps() {
        assert!((phase_from_secs(2.5, 2.5) - 0.0).abs() < 1e-5);
        assert!((phase_from_secs(1.25, 2.5) - 0.5).abs() < 1e-5);
    }
}
