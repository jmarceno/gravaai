"""
Application identity for the About dialog: description, project links, authorship,
and a best-effort runtime version resolver.

The version is not baked into the source tree — releases substitute ``@VERSION@``
into the packaging metadata at build time (see ``linux/packaging/``), so the only
authoritative version at runtime is what pacman recorded when the app was
installed. ``resolve_version()`` queries that and returns ``None`` when it can't
be determined (e.g. running from a source checkout), in which case the About
dialog simply omits the version line.

The querying is a thin GTK-free wrapper; the parsing is pure and unit-tested via an
injectable ``run_fn`` (mirrors the ``services.system_installer`` test-seam pattern).
"""

from __future__ import annotations

import logging
import subprocess
from collections.abc import Callable

from ..config.defaults import APP_ID, APP_NAME

logger = logging.getLogger(__name__)

__all__ = [
    "APP_ID",
    "APP_NAME",
    "DESCRIPTION",
    "REPOSITORY",
    "ISSUE_URL",
    "DEVELOPER_NAME",
    "DEVELOPERS",
    "COPYRIGHT",
    "PACKAGE_NAME",
    "resolve_version",
]

DESCRIPTION = "Records meetings and generates transcripts and structured notes using AI."
REPOSITORY = "https://github.com/jmarceno/gravaai"
ISSUE_URL = f"{REPOSITORY}/issues"
DEVELOPER_NAME = "jmarceno"
DEVELOPERS = ["jmarceno <https://github.com/jmarceno>"]
COPYRIGHT = "© 2026 jmarceno (hard fork of Dipak Yadav's meeting-recorder)"

# The name the Arch package is installed under (see linux/packaging/).
PACKAGE_NAME = "meeting-recorder"

# A runner returns a command's stdout, or None if the command is missing / fails.
RunFn = Callable[[list[str]], str | None]


def _default_run(argv: list[str]) -> str | None:
    """Run ``argv`` and return stripped stdout, or None if it isn't available/fails."""
    try:
        result = subprocess.run(
            argv,
            capture_output=True,
            text=True,
            timeout=5,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        logger.debug("version query %s failed: %s", argv[0], exc)
        return None
    if result.returncode != 0:
        return None
    return result.stdout


def _parse_pacman(stdout: str) -> str | None:
    """``pacman -Q pkg`` prints ``<name> <version>``; take the version field."""
    parts = stdout.split()
    if len(parts) >= 2:
        return parts[1]
    return None


# Single probe: pacman is the only supported package manager (Arch-only fork).
_VERSION_QUERIES: list[tuple[list[str], Callable[[str], str | None]]] = [
    (["pacman", "-Q", PACKAGE_NAME], _parse_pacman),
]


def resolve_version(run_fn: RunFn | None = None) -> str | None:
    """Return the installed package version, or None if it can't be determined.

    Queries pacman; returns None when pacman doesn't know the package — e.g. a
    source checkout — so callers can gracefully omit the version.
    """
    run = run_fn or _default_run
    for argv, parse in _VERSION_QUERIES:
        stdout = run(argv)
        if stdout is None:
            continue
        version = parse(stdout)
        if version:
            return version
    return None
