"""Execute actual release attachment steps with local fake gh; never contacts GitHub."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
OUT = HERE / "release-controls"
OUT.mkdir(exist_ok=True)
results = []
for platform, purpose, name in [
    ("android", "formal", "Attach the APK to the release"),
    ("ios", "formal", "Attach the IPA to the release"),
    ("android", "development", "Publish to the dev-latest pre-release"),
    ("ios", "development", "Publish to the dev-latest pre-release"),
]:
    workflow = ROOT / ".github" / "workflows" / (platform + ".yml")
    data = yaml.safe_load(workflow.read_text())
    step = next(s for job in data["jobs"].values() for s in job["steps"] if s.get("name") == name)
    source = step["run"]
    (OUT / (platform + "-" + purpose + "-original-step.txt")).write_text(source)
    body = source.replace("${{ steps.release.outputs.signed }}", "true").replace("${{ github.event.release.tag_name }}", "audit-local-only")
    for failures, case in [(0, "first-success"), (2, "third-success"), (99, "all-fail")]:
        with tempfile.TemporaryDirectory(prefix="aenht-local-release-") as temporary:
            root = Path(temporary)
            binary = root / "bin"
            binary.mkdir()
            (binary / "gh").write_text("#!/usr/bin/python3\nimport os,pathlib,sys\nif sys.argv[1:3] != ['release','upload']: sys.exit(0)\np=pathlib.Path(os.environ['AUDIT_COUNTER'])\nn=int(p.read_text())+1 if p.exists() else 1\np.write_text(str(n))\nprint('fake-gh-attempt',n,*sys.argv[1:])\nsys.exit(1 if n<=int(os.environ['AUDIT_FAILURES']) else 0)\n")
            (binary / "sleep").write_text("#!/bin/sh\nexit 0\n")
            for p in binary.iterdir(): p.chmod(0o755)
            for rel in ["androidApp/build.gradle.kts", "iosApp/project.yml"]:
                dst = root / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / rel, dst)
            for rel in ["dist/AENHT.apk", "iosApp/build/AENHT.ipa"]:
                dst = root / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_text("synthetic local attachment fixture; not an app")
            env = os.environ.copy()
            env.pop("GH_TOKEN", None)
            env.pop("GITHUB_TOKEN", None)
            env.update(PATH=str(binary) + os.pathsep + env["PATH"], AUDIT_COUNTER=str(root / "counter"), AUDIT_FAILURES=str(failures))
            run = subprocess.run(["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", body], cwd=root, env=env, text=True, capture_output=True)
            attempts = int((root / "counter").read_text()) if (root / "counter").exists() else 0
            expected_success = case != "all-fail"
            item = dict(platform=platform, purpose=purpose, case=case, attempts=attempts, exit_code=run.returncode, expected_success=expected_success,
                        contract_passed=(run.returncode == 0) == expected_success, stdout=run.stdout, stderr=run.stderr)
            results.append(item)
(OUT / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(results, ensure_ascii=False, indent=2))
