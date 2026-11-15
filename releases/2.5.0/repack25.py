"""Make vcraft-2.5.0.jar from vcraft-2.4.0.jar: swap in every recompiled class
(and add the new ones), add the new keys' names to the language file, and bump
the version in fabric.mod.json and the manifest. Every other entry is copied
byte for byte.

    python repack25.py <vcraft-2.4.0.jar> <classes dir> <out jar>
"""
import hashlib, json, pathlib, sys, zipfile

src, classes, out = (pathlib.Path(a) for a in sys.argv[1:4])
OLD, NEW = "2.4.0", "2.5.0"
replace = {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob("*.class")}
NEW_KEYS = {
    "key.vcraft.accept_call": "Accept Call",
    "key.vcraft.hang_up": "Hang Up / Decline",
    "key.vcraft.compact_hud": "Toggle Compact HUD",
}

with zipfile.ZipFile(src) as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
    seen = set()
    for info in zin.infolist():
        data = zin.read(info.filename)
        name = info.filename
        if name in replace:
            data = replace[name]
            seen.add(name)
        elif name == "fabric.mod.json":
            text = data.decode()
            assert f'"version": "{OLD}"' in text, "unexpected fabric.mod.json"
            data = text.replace(f'"version": "{OLD}"', f'"version": "{NEW}"').encode()
        elif name == "META-INF/MANIFEST.MF":
            text = data.decode()
            lines = [l for l in text.splitlines() if not l.startswith("Implementation-Version:")]
            lines.insert(2, f"Implementation-Version: {NEW}")
            data = ("\r\n".join(l for l in lines if l) + "\r\n\r\n").encode()
        elif name == "assets/vcraft/lang/en_us.json":
            lang = json.loads(data.decode())
            lang.update(NEW_KEYS)
            data = (json.dumps(lang, indent=2, ensure_ascii=False) + "\n").encode()
        zout.writestr(info, data)
    for name in sorted(set(replace) - seen):
        info = zipfile.ZipInfo(name, (2026, 11, 11, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        zout.writestr(info, replace[name])
    print("replaced", len(seen), "classes; added", len(set(replace) - seen))

print(out, out.stat().st_size, "bytes, sha256", hashlib.sha256(out.read_bytes()).hexdigest())
