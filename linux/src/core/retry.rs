//! Retry helper for transient network failures.

use std::thread;
use std::time::Duration;

/// HTTP statuses worth retrying: server-side failures and rate limiting.
pub fn is_transient_status(status: u16) -> bool {
    (500..600).contains(&status) || status == 429
}

/// Best-effort classification of an `anyhow` error chain as transient.
///
/// Matches reqwest timeout/connect errors by message, and HTTP status errors
/// carrying a `status` in the 5xx/429 range.
pub fn is_transient(err: &anyhow::Error) -> bool {
    if let Some(e) = err.downcast_ref::<reqwest::Error>() {
        if e.is_timeout() || e.is_connect() || e.is_body() || e.is_decode() {
            return true;
        }
        if let Some(status) = e.status() {
            return is_transient_status(status.as_u16());
        }
    }
    // reqwest::blocking path surfaces the same type; also sniff the chain text.
    let mut chain = err.chain();
    if let Some(first) = chain.next() {
        let msg = format!("{first:?}").to_lowercase();
        if msg.contains("timed out")
            || msg.contains("timeout")
            || msg.contains("connection reset")
            || msg.contains("connection refused")
            || msg.contains("transient http")
        {
            return true;
        }
    }
    for cause in chain {
        let msg = format!("{cause:?}").to_lowercase();
        if msg.contains("timed out") || msg.contains("timeout") {
            return true;
        }
    }
    false
}

/// Call `f()`, retrying up to `retries` times on transient failures.
/// Backoff doubles per attempt (2s, 4s, ...). Non-transient errors propagate
/// immediately; the last transient error propagates once attempts are exhausted.
pub fn retry_on_transient<T, F>(mut f: F, description: &str, retries: u32) -> anyhow::Result<T>
where
    F: FnMut() -> anyhow::Result<T>,
{
    let mut attempt: u32 = 0;
    loop {
        match f() {
            Ok(v) => return Ok(v),
            Err(e) => {
                if attempt >= retries || !is_transient(&e) {
                    return Err(e);
                }
                let delay = 2u64.pow(attempt) * 2;
                attempt += 1;
                log::warn!(
                    "Transient failure in {description} (attempt {attempt}/{retries}): {e:#} — retrying in {delay}s"
                );
                thread::sleep(Duration::from_secs(delay));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

    #[test]
    fn transient_statuses() {
        assert!(is_transient_status(429));
        assert!(is_transient_status(500));
        assert!(is_transient_status(503));
        assert!(!is_transient_status(400));
        assert!(!is_transient_status(401));
        assert!(!is_transient_status(404));
    }

    #[test]
    fn retries_then_succeeds() {
        let calls = Arc::new(AtomicUsize::new(0));
        let c = calls.clone();
        let r = retry_on_transient(
            || {
                let n = c.fetch_add(1, Ordering::SeqCst);
                if n < 2 {
                    Err(anyhow::anyhow!("connection reset by peer"))
                } else {
                    Ok("ok")
                }
            },
            "test op",
            3,
        );
        // "connection reset" matches the transient sniff, so it retries.
        assert_eq!(r.unwrap(), "ok");
        assert_eq!(calls.load(Ordering::SeqCst), 3);
    }

    #[test]
    fn permanent_fails_immediately() {
        let calls = Arc::new(AtomicUsize::new(0));
        let c = calls.clone();
        let r: anyhow::Result<()> = retry_on_transient(
            || {
                c.fetch_add(1, Ordering::SeqCst);
                Err(anyhow::anyhow!("401 Unauthorized: bad api key"))
            },
            "test op",
            3,
        );
        assert!(r.is_err());
        assert_eq!(calls.load(Ordering::SeqCst), 1);
    }
}
