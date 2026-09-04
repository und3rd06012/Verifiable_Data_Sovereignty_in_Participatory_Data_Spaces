#!/usr/bin/env python3
import re, glob

CONJ = {'and', 'but', 'or', 'nor', 'so', 'yet'}

def fix_semis(text):
    parts = re.split(r'(\\texttt\{[^{}]*\})', text)
    out = []
    for part in parts:
        if part.startswith('\\texttt'):
            out.append(part)
            continue
        def repl(m):
            nxt = m.group(1)
            rest = part[m.end():]
            word = re.match(r'([A-Za-z]+)', rest)
            w = word.group(1) if word else ''
            if nxt.isupper():
                return '. ' + nxt
            if w.lower() in CONJ and w == w.lower():
                return ', ' + nxt
            return '. ' + nxt.upper()
        part = re.sub(r';\s+([a-zA-Z"\\])', repl, part)
        out.append(part)
    return ''.join(out)

for fn in sorted(glob.glob('0*.tex')):
    with open(fn) as f:
        t = f.read()
    before = t.count(';')
    t2 = fix_semis(t)
    after = t2.count(';')
    with open(fn, 'w') as f:
        f.write(t2)
    print(fn, 'semicolons:', before, '->', after)
