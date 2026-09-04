//! Opt-in install services (distro-neutral).
//!
//! Security posture: no shell execution — every command is an argv list run
//! without a shell and logged before it runs. Downloads are verified
//! (pinned SHA-256 for the engine; size + logged SHA-256 for the Ollama
//! script) — never `curl | sh`. Nothing here needs privilege escalation or
//! a compiler: installs land in the user data dir. System programs are never
//! installed — when one is missing the app tells the user (see
//! `utils::dependencies`).

use std::path::{Path, PathBuf};
use std::process::Command;

/// Ollama is installed from a versioned, architecture-specific release
/// archive. The version is intentionally pinned so upgrades are explicit and
/// reproducible; changing it also requires updating the release checksums.
pub const OLLAMA_RELEASE_VERSION: &str = "0.11.4";
pub const OLLAMA_RELEASE_BASE: &str = "https://github.com/ollama/ollama/releases/download";

fn log_cmd(cmd: &[String]) {
    log::info!("Running: {}", cmd.join(" "));
}

pub fn run_command(cmd: &[String]) -> i32 {
    log_cmd(cmd);
    Command::new(&cmd[0])
        .args(&cmd[1..])
        .status()
        .map(|s| s.code().unwrap_or(1))
        .unwrap_or(1)
}

pub fn which(bin: &str) -> Option<String> {
    std::env::var_os("PATH").and_then(|paths| {
        std::env::split_paths(&paths).find_map(|dir| {
            let c = dir.join(bin);
            if c.is_file() {
                Some(c.to_string_lossy().into_owned())
            } else {
                None
            }
        })
    })
}

pub struct OllamaInstaller;

impl OllamaInstaller {
    pub fn binary_path() -> PathBuf {
        dirs::home_dir()
            .unwrap_or_else(|| PathBuf::from("~"))
            .join(".local/share/gravaai/ollama/ollama")
    }

    pub fn is_available() -> bool {
        Self::binary_path().is_file() || which("ollama").is_some()
    }

    pub fn install(on_status: &dyn Fn(&str)) -> anyhow::Result<()> {
        let arch = match std::env::consts::ARCH {
            "x86_64" => "amd64",
            "aarch64" => "arm64",
            other => anyhow::bail!("Ollama has no prebuilt Linux archive for {other}"),
        };
        let file_name = format!("ollama-linux-{arch}.tgz");
        let base = format!("{OLLAMA_RELEASE_BASE}/v{OLLAMA_RELEASE_VERSION}");
        let archive_url = format!("{base}/{file_name}");
        let checksums_url = format!("{base}/sha256sums.txt");
        on_status(&format!(
            "Downloading Ollama {OLLAMA_RELEASE_VERSION} ({arch})…"
        ));
        let client = reqwest::blocking::Client::builder()
            .timeout(std::time::Duration::from_secs(3600))
            .build()?;
        let archive: Vec<u8> = client
            .get(&archive_url)
            .send()
            .map_err(|e| anyhow::anyhow!("Failed to fetch Ollama release: {e:#}"))?
            .error_for_status()
            .map_err(|e| anyhow::anyhow!("Ollama release download failed: {e:#}"))?
            .bytes()
            .map_err(|e| anyhow::anyhow!("Failed to read Ollama release: {e:#}"))?
            .to_vec();
        let checksums = client
            .get(&checksums_url)
            .send()
            .map_err(|e| anyhow::anyhow!("Failed to fetch Ollama checksums: {e:#}"))?
            .error_for_status()
            .map_err(|e| anyhow::anyhow!("Ollama checksum download failed: {e:#}"))?
            .text()
            .map_err(|e| anyhow::anyhow!("Failed to read Ollama checksums: {e:#}"))?;
        let expected = checksums
            .lines()
            .find_map(|line| {
                let mut fields = line.split_whitespace();
                let digest = fields.next()?;
                let name = fields.next()?.trim_start_matches('*');
                (name == file_name).then(|| digest.to_ascii_lowercase())
            })
            .ok_or_else(|| anyhow::anyhow!("Ollama checksums do not list {file_name}"))?;
        let actual = sha256_hex(&archive);
        if actual != expected {
            anyhow::bail!("Ollama archive integrity check failed (SHA-256 mismatch)");
        }
        log::info!("Verified Ollama {file_name} (sha256={actual})");

        let root = Self::binary_path();
        let install_dir = root
            .parent()
            .ok_or_else(|| anyhow::anyhow!("Invalid Ollama install path"))?;
        let stage = install_dir.join(format!(".stage-{}", std::process::id()));
        if stage.exists() {
            std::fs::remove_dir_all(&stage)?;
        }
        std::fs::create_dir_all(&stage)?;
        let result = extract_ollama_archive(&archive, &stage)
            .and_then(|source| install_ollama_binary(&source, &root));
        let _ = std::fs::remove_dir_all(&stage);
        result?;
        on_status("Ollama is ready.");
        Ok(())
    }
}

fn extract_ollama_archive(archive: &[u8], stage: &Path) -> anyhow::Result<PathBuf> {
    let decoder = flate2::read::GzDecoder::new(std::io::Cursor::new(archive));
    let mut tar = tar::Archive::new(decoder);
    let mut binary = None;
    for entry in tar.entries()? {
        let mut entry = entry?;
        let entry_type = entry.header().entry_type();
        if entry_type.is_symlink() || entry_type.is_hard_link() {
            anyhow::bail!("Ollama archive contains an unsupported link entry");
        }
        let relative = entry.path()?.into_owned();
        if relative.is_absolute()
            || relative
                .components()
                .any(|component| matches!(component, std::path::Component::ParentDir))
        {
            anyhow::bail!(
                "Ollama archive contains an unsafe path: {}",
                relative.display()
            );
        }
        let destination = stage.join(&relative);
        if let Some(parent) = destination.parent() {
            std::fs::create_dir_all(parent)?;
        }
        entry.unpack(&destination)?;
        if destination.file_name().and_then(|name| name.to_str()) == Some("ollama")
            && destination.is_file()
        {
            binary = Some(destination);
        }
    }
    binary.ok_or_else(|| anyhow::anyhow!("Ollama release archive did not contain an ollama binary"))
}

fn install_ollama_binary(source: &Path, destination: &Path) -> anyhow::Result<()> {
    if let Some(parent) = destination.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let temporary = destination.with_extension("new");
    std::fs::copy(source, &temporary)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt as _;
        std::fs::set_permissions(&temporary, std::fs::Permissions::from_mode(0o755))?;
    }
    std::fs::rename(temporary, destination)?;
    Ok(())
}

pub fn sha256_hex(data: &[u8]) -> String {
    // Minimal SHA-256 (public domain algorithm) to avoid an extra dependency.
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
        0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
        0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
        0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
        0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
        0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
        0xc67178f2,
    ];
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
        0x5be0cd19,
    ];
    let mut msg = data.to_vec();
    let bit_len = (data.len() as u64).wrapping_mul(8);
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&bit_len.to_be_bytes());
    for chunk in msg.chunks(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([
                chunk[4 * i],
                chunk[4 * i + 1],
                chunk[4 * i + 2],
                chunk[4 * i + 3],
            ]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }
        let (mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut hh) =
            (h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7]);
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let t1 = hh
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);
            hh = g;
            g = f;
            f = e;
            e = d.wrapping_add(t1);
            d = c;
            c = b;
            b = a;
            a = t1.wrapping_add(t2);
        }
        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
        h[5] = h[5].wrapping_add(f);
        h[6] = h[6].wrapping_add(g);
        h[7] = h[7].wrapping_add(hh);
    }
    h.iter().map(|x| format!("{x:08x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha256_known_vector() {
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }
}
