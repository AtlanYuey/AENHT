"""Removes keys (no longer used) from keys.json, every <lang>.py and the zh-Hans file.
   python3 tools/i18n/remove.py "Key one" "Key two" ..."""
import json, re, sys, os
ROOT = os.path.dirname(os.path.abspath(__file__))
drop = set(sys.argv[1:])
keys = json.load(open(f'{ROOT}/keys.json', encoding='utf-8'))
idx = [i for i, k in enumerate(keys) if k in drop]
for lang in ['ja', 'zh-Hant', 'ko', 'es', 'fr', 'de', 'it', 'pt-BR', 'ru', 'vi', 'th', 'id']:
    path = f'{ROOT}/{lang}.py'
    ns = {}; exec(open(path, encoding='utf-8').read(), ns); T = ns['T']
    assert len(T) == len(keys), lang
    kept = [v for i, v in enumerate(T) if i not in set(idx)]
    with open(path, 'w', encoding='utf-8') as f:
        f.write('T = [\n' + ''.join('    ' + json.dumps(v, ensure_ascii=False) + ',\n' for v in kept) + ']\n')
json.dump([k for k in keys if k not in drop], open(f'{ROOT}/keys.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
open(f'{ROOT}/keys.json', 'a').write('\n')
zh = f'{ROOT}/../../iosApp/AENHT/Resources/zh-Hans.lproj/Localizable.strings'
lines = open(zh, encoding='utf-8').read().split('\n')
def key_of(line):
    m = re.match(r'^"((?:[^"\\]|\\.)*)" =', line)
    return m and m.group(1).replace('\\"', '"').replace('\\\\', '\\')
open(zh, 'w', encoding='utf-8').write('\n'.join(l for l in lines if key_of(l) not in drop))
print('removed', len(idx))
