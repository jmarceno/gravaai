//! Window-outlives-daemon exit policy.

/// A spawned window must never outlive its daemon: exit when the Engine bus
/// name vanishes — but only after it was seen owned, so a startup race can't
/// kill the window early.
pub fn should_exit_on_owner_change(daemon_seen_owned: bool, has_owner: bool) -> bool {
    daemon_seen_owned && !has_owner
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn policy() {
        assert!(should_exit_on_owner_change(true, false));
        assert!(!should_exit_on_owner_change(false, false)); // startup race
        assert!(!should_exit_on_owner_change(true, true));
        assert!(!should_exit_on_owner_change(false, true));
    }
}
