"""Fixed production/test scopes; raw results stay in .dev/slim/<label>."""
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SCOPE = json.loads((ROOT / "config/slim-scope.json").read_text())
OUT = ROOT / ".dev/slim" / sys.argv[1]
OUT.mkdir(parents=True, exist_ok=True)
for category in ("production", "tests"):
    paths = SCOPE[category]
    tokei = ROOT / ".dev/tooling/quality/tokei-12.1.2.exe"
    if tokei.is_file():
        command = [str(tokei), "--output", "json", "--hidden", "--no-ignore"]
        for pattern in SCOPE["exclude"]:
            command += ["--exclude", pattern]
        result = subprocess.run(command + ["--"] + paths, cwd=ROOT, capture_output=True)
        (OUT / f"tokei-{category}.json").write_bytes(result.stdout)
        if result.returncode:
            raise RuntimeError(result.stderr.decode())
    elif category == "production":
        print("tokei 12.1.2: N/A (fixed binary unavailable)")
node = shutil.which("node")
cli = ROOT / ".dev/tooling/quality/node_modules/jscpd/bin/jscpd"
subprocess.run([node, str(cli), "--config", "config/jscpd.json", "--output",
                str(OUT / "jscpd"), *SCOPE["production"]], cwd=ROOT, check=True)
