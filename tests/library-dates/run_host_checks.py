#!/usr/bin/env python3
"""Compile and exercise the real LibraryDates helper without Android or Gradle."""

from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]


def main():
    sources = [
        ROOT / "app/src/main/java/app/nottheomi/ai/LibraryDates.java",
        ROOT / "tests/library-dates/LibraryDatesTest.java",
    ]
    print(f"LibraryDates host checks: {ROOT}", flush=True)
    with tempfile.TemporaryDirectory(prefix="nottheomi-library-dates-host-") as work:
        subprocess.run(
            [
                "javac", "--release", "17", "-encoding", "UTF-8",
                "-Xlint:all", "-Werror", "-d", work, *map(str, sources),
            ],
            check=True,
            cwd=ROOT,
            timeout=60,
        )
        # Deliberately unrelated defaults catch accidental system-zone grouping.
        for default_zone in ("Pacific/Honolulu", "Asia/Tokyo"):
            subprocess.run(
                [
                    "java", "-ea", "-Dfile.encoding=UTF-8",
                    f"-Duser.timezone={default_zone}", "-cp", work,
                    "app.nottheomi.ai.LibraryDatesTest",
                ],
                check=True,
                cwd=ROOT,
                timeout=60,
            )
    print("LibraryDates host checks: PASS; temporary classes removed", flush=True)


if __name__ == "__main__":
    main()
