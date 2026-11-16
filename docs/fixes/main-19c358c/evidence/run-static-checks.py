"""Read-only product validation; resource generation runs in an isolated directory."""
from pathlib import Path
import ast
from collections import Counter
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
paths = [Path(p) for p in subprocess.check_output(["git", "ls-files"], cwd=ROOT, text=True).splitlines() if not p.startswith(("docs/audits/", "docs/fixes/"))]
counts = Counter()
errors = []
for rel in paths:
    p = ROOT / rel
    try:
        if p.suffix == ".py": ast.parse(p.read_text()); counts["python_ast"] += 1
        if p.suffix == ".js": subprocess.run(["node", "--check", str(p)], capture_output=True, text=True, check=True); counts["javascript_syntax"] += 1
        if p.suffix == ".json": json.loads(p.read_text()); counts["json"] += 1
        if p.suffix == ".xml": ET.parse(p); counts["xml"] += 1
        if p.suffix == ".svg": ET.parse(p); counts["svg"] += 1
        if p.suffix in (".yaml", ".yml"): yaml.safe_load(p.read_text()); counts["yaml"] += 1
        if p.suffix == ".sh" or p.name == "gradlew": subprocess.run(["bash", "-n", str(p)], capture_output=True, check=True); counts["shell_syntax"] += 1
    except Exception as e: errors.append(dict(file=str(rel),error=str(e)))

generation = []
with tempfile.TemporaryDirectory(prefix="aenht-i18n-main-") as temporary:
    temp = Path(temporary)
    shutil.copytree(ROOT / "tools/i18n", temp / "tools/i18n")
    resources = temp / "iosApp/AENHT/Resources"
    for p in (ROOT / "iosApp/AENHT/Resources").glob("*.lproj/Localizable.strings"):
        dst = resources / p.relative_to(ROOT / "iosApp/AENHT/Resources")
        dst.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(p, dst)
    kt = temp / "androidApp/src/main/java/com/atlanyuey/aenht/core/L10nKeys.kt"
    kt.parent.mkdir(parents=True, exist_ok=True)
    commands = [
        ["python3", str(temp / "tools/i18n/gen.py"), str(resources), "ja", "zh-Hant", "ko", "es", "fr", "de", "it", "pt-BR", "ru", "vi", "th", "id"],
        ["python3", str(temp / "tools/i18n/gen_android.py"), str(resources), str(temp / "androidApp/src/main/res"), str(kt)],
    ]
    logs = []
    for command in commands:
        r = subprocess.run(command, cwd=temp, capture_output=True, text=True)
        logs.append(r.stdout + r.stderr)
        if r.returncode: errors.append(dict(check="i18n generator", exit_code=r.returncode))
    for p in temp.rglob("*"):
        if not p.is_file() or "tools" in p.relative_to(temp).parts: continue
        rel = p.relative_to(temp)
        source = ROOT / rel
        generation.append(dict(file=str(rel), same_as_product=source.exists() and source.read_bytes() == p.read_bytes()))
    (HERE / "i18n-output.txt").write_text("\n".join(logs))

models = []
readme = (ROOT / "models/sr/README.md").read_text()
for name, expected in re.findall(r"\| `([^`]+)` \| [^|]+ \| `([0-9a-f]{64})`", readme):
    actual = hashlib.sha256((ROOT / "models/sr" / name).read_bytes()).hexdigest()
    models.append(dict(file=name, expected=expected, actual=actual, matches=actual == expected))
result = dict(syntax=dict(counts), errors=errors, i18n=generation, models=models)
(HERE / "static-results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(dict(syntax=dict(counts),errors=errors,i18n_files=len(generation),i18n_mismatches=[i for i in generation if not i['same_as_product']],models=models),indent=2))
raise SystemExit(bool(errors) or any(not i["same_as_product"] for i in generation) or any(not i["matches"] for i in models))
