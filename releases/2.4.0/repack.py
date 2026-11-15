"""Make vcraft-2.4.0.jar from vcraft-2.2.0.jar: swap in the recompiled
VoiceEngine/Config, add Denoiser, and bump the version in fabric.mod.json
and the manifest. Every other entry is copied byte for byte."""
import hashlib, pathlib, sys, zipfile

src, classes, out = (pathlib.Path(a) for a in sys.argv[1:4])
OLD, NEW = "2.3.0", "2.4.0"
replace = {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob("*.class")}

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
            assert f'"version": "{OLD}"' in text
            data = text.replace(f'"version": "{OLD}"', f'"version": "{NEW}"').encode()
        elif name == "META-INF/MANIFEST.MF":
            data = data.replace(f"Implementation-Version: {OLD}".encode(), f"Implementation-Version: {NEW}".encode())
        zout.writestr(info, data)
    for name in sorted(set(replace) - seen):
        info = zipfile.ZipInfo(name, (2026, 11, 11, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        zout.writestr(info, replace[name])
        print("added", name)
    print("replaced", sorted(seen))

print(out, out.stat().st_size, "bytes, sha256", hashlib.sha256(out.read_bytes()).hexdigest())

