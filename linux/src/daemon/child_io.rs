//! Child stderr capture helpers.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

/// Join stderr lines and truncate to a short tail for error messages.
pub fn stderr_tail(lines: &[String], max_chars: usize) -> String {
    let joined = lines.join(" | ");
    if joined.len() <= max_chars {
        return joined;
    }
    // Keep the tail (the actual error is usually last).
    format!("…{}", &joined[joined.len() - max_chars..])
}

pub fn stderr_tail_default(lines: &[String]) -> String {
    stderr_tail(lines, 400)
}

/// Thread-safe rolling buffer keeping the last 20 stderr lines.
#[derive(Debug, Default, Clone)]
pub struct StderrTail {
    inner: Arc<Mutex<VecDeque<String>>>,
}

impl StderrTail {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn push(&self, line: String) {
        let mut q = self.inner.lock().unwrap();
        if q.len() >= 20 {
            q.pop_front();
        }
        q.push_back(line);
    }

    pub fn lines(&self) -> Vec<String> {
        self.inner.lock().unwrap().iter().cloned().collect()
    }

    pub fn tail(&self) -> String {
        stderr_tail_default(&self.lines())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tail_truncates_to_end() {
        let lines = vec!["a".repeat(300), "b".repeat(300)];
        let t = stderr_tail(&lines, 400);
        assert!(t.len() <= 400 + 3);
        assert!(t.ends_with(&"b".repeat(300)));
    }

    #[test]
    fn rolling_buffer_caps_at_20() {
        let b = StderrTail::new();
        for i in 0..30 {
            b.push(format!("line {i}"));
        }
        let lines = b.lines();
        assert_eq!(lines.len(), 20);
        assert_eq!(lines[0], "line 10");
    }
}
