"""Tests for the pure version resolver in core.app_info (Arch-only: pacman)."""

from __future__ import annotations

from meeting_recorder.core import app_info


def _runner(responses: dict[str, str | None]):
    """Build a run_fn keyed by the command name (argv[0])."""

    def run(argv: list[str]) -> str | None:
        return responses.get(argv[0])

    return run


def test_resolves_pacman_name_and_version():
    run = _runner({"pacman": "meeting-recorder 1.1.35"})
    assert app_info.resolve_version(run) == "1.1.35"


def test_returns_none_when_pacman_does_not_know_it():
    run = _runner({})  # pacman returns None (source checkout)
    assert app_info.resolve_version(run) is None


def test_pacman_malformed_output_is_unknown():
    run = _runner({"pacman": "meeting-recorder"})
    assert app_info.resolve_version(run) is None


def test_blank_pacman_output_is_treated_as_unknown():
    run = _runner({"pacman": "  \n"})
    assert app_info.resolve_version(run) is None
