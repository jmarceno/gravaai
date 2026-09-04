//! Small, shell-free desktop-entry discovery and launch helpers.
//!
//! The Local Tools page uses the portal/direct argv path instead of an external
//! opener or a shell. Desktop entries are untrusted input: only the `Exec=`
//! line from the `[Desktop
//! Entry]` group is parsed, field codes are discarded (there is no file to
//! substitute when opening a tool), and the resulting argv is passed directly
//! to `Command`.

use std::path::{Path, PathBuf};
use std::process::{Child, Command};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesktopLaunch {
    pub executable: PathBuf,
    pub args: Vec<String>,
}

/// Search the standard XDG application directories for one of the known
/// Lepramim desktop IDs.  The explicit user directory is kept even when a
/// custom `XDG_DATA_HOME` is set because older installs may still be there.
pub fn find_lepramim_entry() -> Option<PathBuf> {
    let mut names = vec!["lepramim.desktop", "org.lepramim.App.desktop"];
    let mut dirs = Vec::new();
    if let Some(data_home) = std::env::var_os("XDG_DATA_HOME") {
        dirs.push(PathBuf::from(data_home));
    }
    if let Some(home) = dirs::home_dir() {
        dirs.push(home.join(".local/share"));
    }
    let data_dirs = std::env::var_os("XDG_DATA_DIRS")
        .map(|value| std::env::split_paths(&value).collect::<Vec<_>>())
        .filter(|paths| !paths.is_empty())
        .unwrap_or_else(|| {
            vec![
                PathBuf::from("/usr/local/share"),
                PathBuf::from("/usr/share"),
            ]
        });
    dirs.extend(data_dirs);
    dirs.dedup();
    names.dedup();
    dirs.into_iter()
        .flat_map(|dir| {
            names
                .iter()
                .map(move |name| dir.join("applications").join(name))
        })
        .find(|path| path.is_file())
}

/// Parse and resolve a desktop entry's `Exec=` line.  This function is pure
/// apart from executable lookup, which is injected by callers in tests when
/// needed through [`parse_exec`].
pub fn desktop_launch(path: &Path) -> Option<DesktopLaunch> {
    let contents = std::fs::read_to_string(path).ok()?;
    let exec = desktop_exec(&contents)?;
    let argv = parse_exec(exec).ok()?;
    let executable = resolve_executable(argv.first()?)?;
    Some(DesktopLaunch {
        executable,
        args: argv.into_iter().skip(1).collect(),
    })
}

/// Find the best installed Lepramim launch command.  Desktop entries take
/// precedence because they carry the app's canonical command line; PATH is a
/// fallback for portable/source installs.
pub fn find_lepramim_launch() -> Option<DesktopLaunch> {
    if let Some(path) = find_lepramim_entry() {
        if let Some(launch) = desktop_launch(&path) {
            return Some(launch);
        }
    }
    let executable = resolve_executable("lepramim")?;
    Some(DesktopLaunch {
        executable,
        args: Vec::new(),
    })
}

pub fn spawn_launch(launch: &DesktopLaunch) -> std::io::Result<Child> {
    Command::new(&launch.executable).args(&launch.args).spawn()
}

fn desktop_exec(contents: &str) -> Option<&str> {
    let mut in_entry = false;
    for line in contents.lines() {
        let line = line.trim();
        if line.starts_with('[') && line.ends_with(']') {
            in_entry = line == "[Desktop Entry]";
            continue;
        }
        if in_entry && line.strip_prefix("Exec=").is_some() {
            return line
                .strip_prefix("Exec=")
                .map(str::trim)
                .filter(|s| !s.is_empty());
        }
    }
    None
}

/// Tokenize the limited shell-like quoting grammar allowed by desktop Exec.
/// Operators, substitutions and redirections are never interpreted.
pub fn parse_exec(line: &str) -> Result<Vec<String>, &'static str> {
    let mut words = Vec::new();
    let mut current = String::new();
    let mut quote: Option<char> = None;
    let mut escaped = false;
    let mut had_content = false;
    for ch in line.chars() {
        if escaped {
            current.push(ch);
            escaped = false;
            had_content = true;
            continue;
        }
        if ch == '\\' && quote != Some('\'') {
            escaped = true;
            continue;
        }
        if let Some(q) = quote {
            if ch == q {
                quote = None;
            } else {
                current.push(ch);
                had_content = true;
            }
            continue;
        }
        match ch {
            '\'' | '"' => quote = Some(ch),
            c if c.is_whitespace() => {
                if had_content {
                    words.push(std::mem::take(&mut current));
                    had_content = false;
                }
            }
            _ => {
                current.push(ch);
                had_content = true;
            }
        }
    }
    if escaped || quote.is_some() {
        return Err("desktop Exec has an unterminated escape or quote");
    }
    if had_content {
        words.push(current);
    }
    if words.is_empty() {
        return Err("desktop Exec is empty");
    }
    // Field codes are meaningful only when a file/URL is being passed.  The
    // Local Tools button has no such input, so ignore the standard codes and
    // reject everything else rather than handing an accidental argument to a
    // program.
    let mut filtered = Vec::with_capacity(words.len());
    for word in words {
        if word.starts_with('%') {
            if matches!(
                word.as_str(),
                "%f" | "%F" | "%u" | "%U" | "%i" | "%c" | "%k"
            ) {
                continue;
            }
            return Err("desktop Exec contains an unsupported field code");
        }
        if word.contains('%') {
            return Err("desktop Exec contains an embedded field code");
        }
        filtered.push(word);
    }
    if filtered.is_empty() {
        return Err("desktop Exec has no executable after field codes");
    }
    Ok(filtered)
}

fn resolve_executable(program: &str) -> Option<PathBuf> {
    let path = Path::new(program);
    // A desktop entry must not turn this button into an implicit shell
    // launcher.  Lepramim's own entry is a direct executable; reject shell
    // front-ends even though Command itself does not interpret metacharacters.
    if path
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| {
            matches!(
                name,
                "sh" | "bash" | "dash" | "zsh" | "fish" | "csh" | "ksh" | "env"
            )
        })
    {
        return None;
    }
    if path.is_absolute() || program.contains('/') {
        return path.is_file().then(|| path.to_path_buf());
    }
    crate::services::system_installer::which(program).map(PathBuf::from)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_quoted_exec_without_shell_expansion() {
        assert_eq!(
            parse_exec("/opt/Lepramim\\ App/lepramim --open 'a b' %U").unwrap(),
            vec!["/opt/Lepramim App/lepramim", "--open", "a b"]
        );
    }

    #[test]
    fn keeps_shell_metacharacters_literal_and_rejects_codes() {
        assert_eq!(parse_exec("lepramim; rm -rf /").unwrap()[0], "lepramim;");
        assert!(parse_exec("lepramim %Z").is_err());
        assert!(parse_exec("'unterminated").is_err());
    }

    #[test]
    fn extracts_only_desktop_entry_exec() {
        let text = "Exec=wrong\n[Desktop Entry]\nType=Application\nExec=lepramim --new\n[Other]\nExec=nope";
        assert_eq!(desktop_exec(text), Some("lepramim --new"));
    }
}
