#!/usr/bin/env python3
import os, re, sys, unicodedata

ROOT = os.getcwd()
EN_FILES = ["README.md"]
for d, _, files in os.walk("docs"):
    EN_FILES += [os.path.join(d, f) for f in files if f.endswith(".md") and not f.endswith(".zh-CN.md")]
for d, _, files in os.walk("extensions"):
    EN_FILES += [os.path.join(d, f) for f in files if f.endswith(".md") and not f.endswith(".zh-CN.md")]
EN_FILES = sorted(EN_FILES)

def twin(path):
    return path[:-3] + ".zh-CN.md"

def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()

LINK = re.compile(r"(?<!!)\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
FENCE = re.compile(r"```.*?```", re.S)
INLINE = re.compile(r"`[^`\n]*`")
HEADING = re.compile(r"^(#{1,6})\s+(.*)$", re.M)

def slug(text):
    # GitHub: strip inline code/links markup and HTML, lowercase, drop
    # punctuation, spaces -> '-'.
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"`([^`]*)`", r"\1", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = unicodedata.normalize("NFKC", text).lower()
    out = []
    for ch in text:
        cat = unicodedata.category(ch)
        if ch in " -_" or cat[0] in "LN" or cat == "Mn":
            out.append(ch)
    return "".join(out).replace(" ", "-")

def anchors(text):
    seen, out = {}, set()
    for _, h in HEADING.findall(FENCE.sub("", text)):
        s = slug(h)
        n = seen.get(s, 0)
        seen[s] = n + 1
        out.add(s if n == 0 else "%s-%d" % (s, n))
    return out

problems = []
for en in EN_FILES:
    zh = twin(en)
    if not os.path.exists(zh):
        problems.append("%s: missing Chinese twin %s" % (en, zh))
        continue
    # switcher lines
    en_first, zh_first = read(en).splitlines()[0], read(zh).splitlines()[0]
    if "简体中文" not in en_first or "**English**" not in en_first:
        problems.append("%s: first line is not the EN switcher: %r" % (en, en_first))
    if "English" not in zh_first or "简体中文" not in zh_first:
        problems.append("%s: first line is not the ZH switcher: %r" % (zh, zh_first))

    for path in (en, zh):
        text = read(path)
        base = os.path.dirname(path)
        own_anchors = anchors(text)
        body = FENCE.sub("", INLINE.sub("", text))
        for target in LINK.findall(body):
            if target.startswith(("http://", "https://", "mailto:", "tel:")):
                continue
            file_part, _, anchor = target.partition("#")
            if not file_part:
                if anchor not in own_anchors:
                    problems.append("%s: dead anchor %r" % (path, target))
                continue
            dest = os.path.normpath(os.path.join(base, file_part))
            if not os.path.exists(dest):
                problems.append("%s: dead link %r (resolved %s)" % (path, target, dest))
                continue
            if anchor and dest.endswith(".md"):
                if anchor not in anchors(read(dest)):
                    problems.append("%s: dead anchor %r in %s" % (path, target, dest))

print("checked %d doc pairs" % len(EN_FILES))
if problems:
    print("\n".join(problems))
    sys.exit(1)
print("OK")
