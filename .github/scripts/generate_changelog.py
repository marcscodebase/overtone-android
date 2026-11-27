#!/usr/bin/env python3
"""Generate a grouped Markdown changelog from Conventional Commits between two git refs."""

from __future__ import annotations

import os
import re
import subprocess
import sys
from collections import defaultdict

SEMVER_TAG_RE = re.compile(
    r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"
)
CONVENTIONAL_RE = re.compile(
    r"^(?P<type>[a-z]+)(?:\((?P<scope>[^)]+)\))?(?P<breaking>!)?:\s*(?P<desc>.+)$"
)

SECTION_ORDER = [
    ("breaking", "Breaking Changes"),
    ("feat", "Features"),
    ("fix", "Bug Fixes"),
    ("perf", "Performance"),
    ("refactor", "Refactoring"),
    ("docs", "Documentation"),
    ("maintenance", "Build, CI & Maintenance"),
    ("other", "Other Changes"),
]


def run_git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return result.stdout.strip()


def find_previous_semver_tag(current_tag: str) -> str | None:
    try:
        tags_raw = run_git("tag", "--merged", f"{current_tag}^", "--sort=-v:refname")
    except subprocess.CalledProcessError:
        return None
    for line in tags_raw.splitlines():
        candidate = line.strip()
        if candidate and candidate != current_tag and SEMVER_TAG_RE.match(candidate):
            return candidate
    return None


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: generate_changelog.py <current-tag> [output-file]", file=sys.stderr)
        return 1

    current_tag = sys.argv[1].strip()
    output_path = sys.argv[2] if len(sys.argv) > 2 else None
    repo = os.environ.get("GITHUB_REPOSITORY", "").strip()

    if not SEMVER_TAG_RE.match(current_tag):
        print(f"ERROR: Tag '{current_tag}' is not a valid Semantic Version (vMAJOR.MINOR.PATCH[-prerelease]).", file=sys.stderr)
        return 1

    prev_tag = find_previous_semver_tag(current_tag)
    rev_range = f"{prev_tag}..{current_tag}" if prev_tag else current_tag

    raw_log = run_git("log", rev_range, "--no-merges", "--pretty=format:%h%x1f%s")
    sections: dict[str, list[str]] = defaultdict(list)

    for line in raw_log.splitlines():
        if not line.strip():
            continue
        parts = line.split("\x1f", 1)
        if len(parts) != 2:
            continue
        short_sha, subject = parts[0].strip(), parts[1].strip()
        if not subject:
            continue

        sha_ref = f"[`{short_sha}`](https://github.com/{repo}/commit/{short_sha})" if repo else f"`{short_sha}`"
        match = CONVENTIONAL_RE.match(subject)
        if match:
            ctype = match.group("type").lower()
            scope = match.group("scope")
            breaking = bool(match.group("breaking"))
            desc = match.group("desc").strip()
            formatted = f"- **{scope}:** {desc} ({sha_ref})" if scope else f"- {desc} ({sha_ref})"

            if breaking:
                sections["breaking"].append(formatted)
            elif ctype in ("feat", "fix", "perf", "refactor", "docs"):
                sections[ctype].append(formatted)
            elif ctype in ("build", "ci", "chore", "test", "style"):
                sections["maintenance"].append(formatted)
            else:
                sections["other"].append(formatted)
        else:
            sections["other"].append(f"- {subject} ({sha_ref})")

    lines: list[str] = []
    if prev_tag:
        lines.append(f"## What's Changed since `{prev_tag}`")
    else:
        lines.append("## What's Changed")
    lines.append("")

    emitted_any = False
    for key, title in SECTION_ORDER:
        entries = sections.get(key)
        if not entries:
            continue
        emitted_any = True
        lines.append(f"### {title}")
        lines.extend(entries)
        lines.append("")

    if not emitted_any:
        lines.append("- Maintenance release with no user-facing commit changes.")
        lines.append("")

    if repo and prev_tag:
        lines.append(f"**Full Changelog:** https://github.com/{repo}/compare/{prev_tag}...{current_tag}")
        lines.append("")

    markdown = "\n".join(lines).rstrip() + "\n"
    if output_path:
        with open(output_path, "w", encoding="utf-8") as fh:
            fh.write(markdown)
    else:
        sys.stdout.write(markdown)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
