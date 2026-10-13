#!/usr/bin/env python3
"""Reject local files and working documents in the Git index."""
import re
import subprocess
import sys
from pathlib import PurePosixPath


def git_paths(*args):
    result = subprocess.check_output(["git", "ls-files", "-z", *args])
    return {path.decode("utf-8", errors="replace") for path in result.split(b"\0") if path}


def main():
    tracked = git_paths()
    forbidden = git_paths("--cached", "--ignored", "--exclude-standard")
    final_release = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-(?:alpha|beta|rc)[0-9]+(?:\.[0-9]+)?)?\.md")
    working_name = re.compile(r"(?:^|[-_.])(?:audit|report|raport|plan|iteration|iter[0-9]*|notatki)(?:[-_.]|$)", re.IGNORECASE)
    for name in tracked:
        path = PurePosixPath(name)
        if path.suffix.lower() in {".jks", ".keystore", ".pfx", ".p12", ".pem", ".key"} or name.endswith((".pfx.b64", ".p12.b64", ".jks.b64")):
            forbidden.add(name)
        if name.startswith("release-notes/") and (len(path.parts) != 2 or not final_release.fullmatch(path.name)):
            forbidden.add(name)
        if path.suffix.lower() == ".md" and (name.startswith("docs/") or working_name.search(path.stem)):
            forbidden.add(name)
    if forbidden:
        print("Usuń z indeksu lokalne pliki i dokumenty robocze (zachowaj kopie na dysku):", file=sys.stderr)
        for name in sorted(forbidden):
            print(f"  {name}", file=sys.stderr)
        return 1
    print("Repozytorium bez lokalnych plików, raportów roboczych i notatek z iteracji.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
