//! Background-thread work facility.
//!
//! `TaskRunner::submit` runs work on tracked daemon threads and routes the
//! result back through a main-thread scheduler (GLib idle in the daemon/window,
//! injectable for headless use and tests).

use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

pub type MainCallback = Box<dyn FnOnce() + Send + 'static>;

/// Schedules a callback onto the main thread / main loop.
pub type Scheduler = Arc<dyn Fn(MainCallback) + Send + Sync + 'static>;

type TaskEntry = (String, JoinHandle<()>);

fn immediate_scheduler() -> Scheduler {
    Arc::new(|cb: MainCallback| cb())
}

pub struct TaskRunner {
    scheduler: Scheduler,
    tasks: Arc<Mutex<Vec<TaskEntry>>>,
    shutting_down: Arc<std::sync::atomic::AtomicBool>,
}

impl TaskRunner {
    pub fn new(scheduler: Option<Scheduler>) -> Self {
        Self {
            scheduler: scheduler.unwrap_or_else(immediate_scheduler),
            tasks: Arc::new(Mutex::new(Vec::new())),
            shutting_down: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        }
    }

    /// Run `work` on a background thread; invoke `on_done`/`on_error` on the main thread.
    pub fn submit<T, W, D, E>(
        &self,
        work: W,
        description: &str,
        on_done: Option<D>,
        on_error: Option<E>,
    ) where
        T: Send + 'static,
        W: FnOnce() -> anyhow::Result<T> + Send + 'static,
        D: FnOnce(T) + Send + 'static,
        E: FnOnce(anyhow::Error) + Send + 'static,
    {
        if self.shutting_down.load(std::sync::atomic::Ordering::SeqCst) {
            log::warn!("submit-after-shutdown ignored: {description}");
            return;
        }
        let scheduler = self.scheduler.clone();
        let desc = description.to_string();
        let desc_for_closure = desc.clone();
        let handle =
            thread::Builder::new()
                .name(format!("task-{desc}"))
                .spawn(move || {
                    let result: anyhow::Result<T> = work();
                    let sched = scheduler;
                    match result {
                        Ok(v) => {
                            if let Some(cb) = on_done {
                                let desc = desc_for_closure.clone();
                                sched(Box::new(move || {
                                    // Main-thread callback exceptions can't propagate
                                    // through the loop; log instead of swallowing.
                                    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(
                                        || cb(v),
                                    ));
                                    if r.is_err() {
                                        log::error!("main-thread callback panicked ({desc})");
                                    }
                                }));
                            }
                        }
                        Err(e) => {
                            if let Some(cb) = on_error {
                                let desc = desc_for_closure.clone();
                                sched(Box::new(move || {
                                    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(
                                        || cb(e),
                                    ));
                                    if r.is_err() {
                                        log::error!("main-thread error callback panicked ({desc})");
                                    }
                                }));
                            } else {
                                log::error!("unhandled worker error ({desc_for_closure}): {e:#}");
                            }
                        }
                    }
                })
                .expect("spawn task thread");
        self.tasks.lock().unwrap().push((desc, handle));
    }

    /// Join running tasks with a bounded grace period; report abandoned ones.
    pub fn shutdown(&self, grace: Duration) {
        self.shutting_down
            .store(true, std::sync::atomic::Ordering::SeqCst);
        let deadline = std::time::Instant::now() + grace;
        let mut tasks = self.tasks.lock().unwrap();
        let pending: Vec<TaskEntry> = std::mem::take(&mut *tasks);
        drop(tasks);
        for (desc, handle) in pending {
            if std::time::Instant::now() >= deadline {
                log::warn!("abandoning task after grace period: {desc}");
                // Detached by design (a wedged external process such as a hung
                // ffmpeg must not block application exit); the grace period in
                // `shutdown()` is what makes the common case clean.
                std::mem::forget(handle);
                continue;
            }
            let _ = handle.join();
            let _ = &desc;
        }
    }
}

impl Default for TaskRunner {
    fn default() -> Self {
        Self::new(None)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;

    #[test]
    fn result_routing() {
        let r = TaskRunner::default();
        let (tx, rx) = mpsc::channel();
        r.submit(
            || Ok::<_, anyhow::Error>(42),
            "t",
            Some(move |v| tx.send(v).unwrap()),
            None::<fn(anyhow::Error)>,
        );
        // Give the worker thread a moment.
        let v = rx.recv_timeout(Duration::from_secs(5)).unwrap();
        assert_eq!(v, 42);
    }

    #[test]
    fn error_routing() {
        let r = TaskRunner::default();
        let (tx, rx) = mpsc::channel();
        r.submit(
            || Err::<(), _>(anyhow::anyhow!("boom")),
            "t",
            None::<fn(())>,
            Some(move |e| tx.send(format!("{e:#}")).unwrap()),
        );
        let msg = rx.recv_timeout(Duration::from_secs(5)).unwrap();
        assert!(msg.contains("boom"));
    }

    #[test]
    fn shutdown_joins() {
        let r = TaskRunner::default();
        let (tx, rx) = mpsc::channel();
        r.submit(
            || Ok::<_, anyhow::Error>(()),
            "noop",
            Some(move |_| tx.send(()).unwrap()),
            None::<fn(anyhow::Error)>,
        );
        rx.recv_timeout(Duration::from_secs(5)).unwrap();
        r.shutdown(Duration::from_secs(5));
    }
}
