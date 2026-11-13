#!/usr/bin/env python3
"""Summarise JaCoCo CSV reports (target/site/jacoco/jacoco.csv in every module) as a Markdown table.

Usage: python3 scripts/ci/coverage-summary.py [root]   -> prints Markdown (CI appends it to the job summary)
"""
import csv
import pathlib
import sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
rows = []
total = [0, 0, 0, 0]  # line missed, line covered, branch missed, branch covered
for report in sorted(root.glob("**/target/site/jacoco/jacoco.csv")):
    lm = lc = bm = bc = 0
    module = None
    with report.open() as f:
        for r in csv.DictReader(f):
            module = r["GROUP"]
            lm += int(r["LINE_MISSED"]); lc += int(r["LINE_COVERED"])
            bm += int(r["BRANCH_MISSED"]); bc += int(r["BRANCH_COVERED"])
    if module is None:
        continue
    for i, v in enumerate((lm, lc, bm, bc)):
        total[i] += v
    rows.append((module, lm, lc, bm, bc))


def pct(missed, covered):
    return "n/a" if missed + covered == 0 else f"{100 * covered / (missed + covered):.1f}%"


print("### Test coverage (unit + integration tests)\n")
print("| Module | Lines | Branches |")
print("|---|---:|---:|")
for module, lm, lc, bm, bc in rows:
    print(f"| {module} | {pct(lm, lc)} | {pct(bm, bc)} |")
print(f"| **Total** | **{pct(total[0], total[1])}** | **{pct(total[2], total[3])}** |")
