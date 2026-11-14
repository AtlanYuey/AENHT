"""Fills in translations of existing keys.
   python3 tools/i18n/fill.py batch.py   (batch.py: LANGS = [...]; FILL = {key: (one per lang)})"""
import json, sys, os
ROOT = os.path.dirname(os.path.abspath(__file__))
ns = {}; exec(open(sys.argv[1], encoding='utf-8').read(), ns)
LANGS, FILL = ns['LANGS'], ns['FILL']
keys = json.load(open(f'{ROOT}/keys.json', encoding='utf-8'))
unknown = [k for k in FILL if k not in keys]
assert not unknown, unknown
for i, lang in enumerate(LANGS):
    path = f'{ROOT}/{lang}.py'
    ns = {}; exec(open(path, encoding='utf-8').read(), ns); T = ns['T']
    for k, values in FILL.items():
        assert len(values) == len(LANGS), k
        T[keys.index(k)] = values[i]
    with open(path, 'w', encoding='utf-8') as f:
        f.write('T = [\n' + ''.join('    ' + json.dumps(v, ensure_ascii=False) + ',\n' for v in T) + ']\n')
print('filled', len(FILL), 'keys in', len(LANGS), 'languages')
