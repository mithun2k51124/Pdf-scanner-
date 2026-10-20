"""Turns Gradle/Kotlin build errors into GitHub annotations (visible on the run page)."""
import re, sys, os
start = int(sys.argv[1]) if len(sys.argv) > 1 else 0
try:
    log = open("build.log", encoding="utf-8", errors="replace").read().splitlines()
except FileNotFoundError:
    sys.exit(0)
ws = os.environ.get("GITHUB_WORKSPACE", "")
errs = []
pat = re.compile(r"^e: (?:file://)?(\S+?\.kts?):(\d+):(\d+) (.*)$")
for line in log:
    m = pat.match(line)
    if m:
        path = m.group(1).replace(ws + "/", "")
        errs.append(f"::error file={path},line={m.group(2)},col={m.group(3)}::{m.group(4)}")
if not errs:
    for i, line in enumerate(log):
        if line.startswith("* What went wrong"):
            msg = " | ".join(l.strip() for l in log[i + 1:i + 12] if l.strip() and not l.startswith("* Try"))
            errs.append("::error::" + msg[:2000])
        elif "AAPT: error" in line or line.startswith("ERROR:"):
            errs.append("::error::" + line.strip()[:1000])
for e in errs[start:start + 10]:
    print(e.replace("%", "%25").replace("\r", "").replace("\n", "%0A") if not e.startswith("::error") else e)
