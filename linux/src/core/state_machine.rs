//! Recording state machine.

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum State {
    Idle,
    Recording,
    Paused,
    Countdown,
}

impl State {
    pub fn as_str(&self) -> &'static str {
        match self {
            State::Idle => "idle",
            State::Recording => "recording",
            State::Paused => "paused",
            State::Countdown => "countdown",
        }
    }
}

/// Legality table for state jumps. Self-transitions are always allowed.
pub fn can_transition(cur: State, new: State) -> bool {
    if cur == new {
        return true;
    }
    match cur {
        State::Idle => matches!(new, State::Recording),
        State::Recording | State::Paused => true, // any jump among the active states + idle
        State::Countdown => matches!(new, State::Idle),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn self_transitions_allowed() {
        for s in [
            State::Idle,
            State::Recording,
            State::Paused,
            State::Countdown,
        ] {
            assert!(can_transition(s, s));
        }
    }

    #[test]
    fn idle_only_to_recording() {
        assert!(can_transition(State::Idle, State::Recording));
        assert!(!can_transition(State::Idle, State::Paused));
        assert!(!can_transition(State::Idle, State::Countdown));
        assert!(can_transition(State::Idle, State::Idle));
    }

    #[test]
    fn active_states_move_freely() {
        for cur in [State::Recording, State::Paused] {
            for new in [
                State::Idle,
                State::Recording,
                State::Paused,
                State::Countdown,
            ] {
                assert!(can_transition(cur, new), "{cur:?} -> {new:?}");
            }
        }
    }

    #[test]
    fn countdown_only_to_idle() {
        assert!(can_transition(State::Countdown, State::Idle));
        assert!(!can_transition(State::Countdown, State::Recording));
        assert!(!can_transition(State::Countdown, State::Paused));
    }
}
