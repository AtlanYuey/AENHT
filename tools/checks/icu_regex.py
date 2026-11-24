"""Compiles every Kotlin regex literal with ICU, the regex engine of Android
(and of NSRegularExpression on iOS): stricter than the desktop JVM's, which
the unit tests run on. A pattern the JVM takes and ICU refuses crashes the
app on a phone (issue #12: a "}" with no "{" before it).

    python3 tools/checks/icu_regex.py shared/src/commonMain androidApp/src/main

Needs PyICU (Debian / Ubuntu: apt install python3-icu). Exits 1 on a pattern
ICU refuses. Patterns built at run time ($name inside) are listed, not checked."""
import icu, re, sys, pathlib
roots = sys.argv[1:]
call = re.compile(r'(Regex\s*\(|\.toRegex\s*\(|Pattern\.compile\s*\()')
def kotlin_string(src, i):
    """The literal starting at src[i] (after whitespace): (value, end) or None."""
    while i < len(src) and src[i] in ' \n\t': i += 1
    if src.startswith('"""', i):
        j = src.index('"""', i + 3)
        return src[i+3:j], j + 3, True
    if src.startswith('"', i):
        j = i + 1; out = []
        while src[j] != '"':
            if src[j] == '\\':
                n = src[j+1]
                out.append({'n':'\n','t':'\t','r':'\r','\\':'\\','"':'"','$':'$',"'":"'"}.get(n, '\\'+n)); j += 2
            else:
                out.append(src[j]); j += 1
        return ''.join(out), j + 1, False
    return None
bad = ok = dynamic = 0
for root in roots:
    for path in pathlib.Path(root).rglob('*.kt'):
        src = path.read_text(encoding='utf-8')
        for m in call.finditer(src):
            start = m.end()
            if m.group(1).startswith('.toRegex'):
                # the receiver literal: find the string ending right before
                k = m.start() - 1
                if src[k] != '"': continue
                if src[k-2:k+1] == '"""':
                    b = src.rindex('"""', 0, k-2); lit = (src[b+3:k-2], 0, True)
                else:
                    b = src.rindex('"', 0, k); lit = (bytes(src[b+1:k], 'utf-8').decode('unicode_escape'), 0, False)
            else:
                lit = kotlin_string(src, start)
            if not lit: continue
            value = lit[0]
            line = src.count('\n', 0, m.start()) + 1
            if re.search(r'\$\{|\$[a-zA-Z_]', value):
                dynamic += 1
                print(f'DYNAMIC {path}:{line}: {value!r}')
                continue
            try:
                icu.RegexPattern.compile(value); ok += 1
            except Exception as e:
                bad += 1
                print(f'ICU ERROR {path}:{line}: {value!r} -> {e.args[0]} at {e.args[2] if len(e.args)>2 else "?"}')
print(f'\n{ok} compile on ICU, {bad} fail, {dynamic} built at run time (not checked)')
sys.exit(1 if bad else 0)
