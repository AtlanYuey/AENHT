"""Adds strings in every language.
   python3 tools/i18n/add.py new.py   (new.py: NEW = {key: {lang: text}}, langs below)
Then run gen.py and gen_android.py (see README)."""
import json, re, sys, os
ROOT = os.path.dirname(os.path.abspath(__file__))
LANGS = ['zh-Hans', 'zh-Hant', 'ja', 'ko', 'es', 'fr', 'de', 'it', 'pt-BR', 'ru', 'vi', 'th', 'id']
ns = {}; exec(open(sys.argv[1], encoding='utf-8').read(), ns); NEW = ns['NEW']
for k, v in NEW.items():
    missing = [l for l in LANGS if not v.get(l)]
    assert not missing, (k, missing)
keys = json.load(open(f'{ROOT}/keys.json', encoding='utf-8'))
add = [k for k in NEW if k not in set(keys)]
update = [k for k in NEW if k in set(keys)]
for lang in LANGS[1:]:
    path = f'{ROOT}/{lang}.py'
    ns = {}; exec(open(path, encoding='utf-8').read(), ns); T = ns['T']
    for k in update: T[keys.index(k)] = NEW[k][lang]
    T += [NEW[k][lang] for k in add]
    with open(path, 'w', encoding='utf-8') as f:
        f.write('T = [\n' + ''.join('    ' + json.dumps(v, ensure_ascii=False) + ',\n' for v in T) + ']\n')
json.dump(keys + add, open(f'{ROOT}/keys.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
open(f'{ROOT}/keys.json', 'a').write('\n')
zh = f'{ROOT}/../../iosApp/AENHT/Resources/zh-Hans.lproj/Localizable.strings'
def esc(s): return s.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
text = open(zh, encoding='utf-8').read()
for k in NEW:
    line = f'"{esc(k)}" = "{esc(NEW[k]["zh-Hans"])}";'
    pattern = re.compile(r'^"' + re.escape(esc(k)) + r'" = .*$', re.M)
    text = pattern.sub(lambda _: line, text) if pattern.search(text) else text.rstrip('\n') + '\n' + line + '\n'
open(zh, 'w', encoding='utf-8').write(text)
print('added', len(add), 'updated', len(update))
