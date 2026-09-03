//! Window spawn-vs-present decision.

/// Supervises the single window child: spawn it when dead, ask the existing
/// one to present itself otherwise. Spawn/present primitives are injected so
/// the decision is unit-testable without processes or a bus.
pub struct WindowSupervisor {
    spawn_fn: Box<dyn FnMut() + Send>,
    present_fn: Box<dyn FnMut() + Send>,
    alive: bool,
}

impl WindowSupervisor {
    pub fn new(
        spawn_fn: impl FnMut() + Send + 'static,
        present_fn: impl FnMut() + Send + 'static,
    ) -> Self {
        Self {
            spawn_fn: Box::new(spawn_fn),
            present_fn: Box::new(present_fn),
            alive: false,
        }
    }

    pub fn open(&mut self) {
        if self.alive {
            (self.present_fn)();
        } else {
            self.alive = true;
            (self.spawn_fn)();
        }
    }

    pub fn on_child_exit(&mut self) {
        self.alive = false;
    }

    #[cfg(test)]
    pub fn is_alive(&self) -> bool {
        self.alive
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    #[test]
    fn spawn_then_present() {
        let calls: Arc<Mutex<Vec<&str>>> = Arc::new(Mutex::new(Vec::new()));
        let c1 = calls.clone();
        let c2 = calls.clone();
        let mut s = WindowSupervisor::new(
            move || c1.lock().unwrap().push("spawn"),
            move || c2.lock().unwrap().push("present"),
        );
        s.open();
        s.open();
        assert_eq!(*calls.lock().unwrap(), vec!["spawn", "present"]);
        s.on_child_exit();
        assert!(!s.is_alive());
        s.open();
        assert_eq!(*calls.lock().unwrap(), vec!["spawn", "present", "spawn"]);
    }
}
