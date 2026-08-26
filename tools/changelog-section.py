#!/usr/bin/env python3
"""Extrait du CHANGELOG la section d'une version, pour en faire les notes de release.

Usage : changelog-section.py v1.0.0
Sort le corps de la section « ## v1.0.0 » (ou « ## [1.0.0] »), sans son titre.
Code de retour 1 si la section n'existe pas : l'appelant retombe alors sur un
texte générique plutôt que de publier une release sans notes.
"""
import re
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: changelog-section.py <version>", file=sys.stderr)
        return 2
    wanted = sys.argv[1].lstrip("v").strip()
    path = Path(__file__).resolve().parent.parent / "CHANGELOG.md"
    if not path.exists():
        return 1

    lines = path.read_text(encoding="utf-8").splitlines()
    heading = re.compile(r"^##\s+\[?v?([0-9][^\]\s]*)\]?")
    start = None
    for index, line in enumerate(lines):
        match = heading.match(line)
        if not match:
            continue
        if start is None and match.group(1).strip() == wanted:
            start = index + 1
        elif start is not None:
            print("\n".join(lines[start:index]).strip())
            return 0
    if start is None:
        return 1
    print("\n".join(lines[start:]).strip())
    return 0


if __name__ == "__main__":
    sys.exit(main())
