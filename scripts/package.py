"""Build and verify the installable module ZIP, with UNIX permissions and no wrapper folder."""
from pathlib import Path
import hashlib
import zipfile

root = Path(__file__).resolve().parents[1]
stage = root / "build/module"
out = root / "dist/GuestGuard-v2.0.0.zip"
required = {"module.prop", "companion.apk", "customize.sh", "action.sh", "service.sh", "bin/guestctl.sh",
            "META-INF/com/google/android/update-binary", "META-INF/com/google/android/updater-script"}
assert all((stage / name).is_file() for name in required), "Incomplete module; refusing to package"
with zipfile.ZipFile(stage / "companion.apk") as apk:
    assert apk.testzip() is None
    assert {"classes.dex", "AndroidManifest.xml"} <= set(apk.namelist())
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for p in sorted(stage.rglob("*")):
        if p.is_file():
            name = p.relative_to(stage).as_posix()
            entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            entry.create_system = 3
            executable = name.endswith(".sh") or name.endswith("update-binary")
            entry.external_attr = (0o100755 if executable else 0o100644) << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(entry, p.read_bytes())
with zipfile.ZipFile(out) as z:
    assert z.testzip() is None
    assert required <= set(z.namelist())
    for name in required:
        mode = (z.getinfo(name).external_attr >> 16) & 0o777
        expected = 0o755 if name.endswith(".sh") or name.endswith("update-binary") else 0o644
        assert mode == expected, f"Incorrect ZIP permissions: {name}"
    assert z.read("META-INF/com/google/android/updater-script").strip() == b"#MAGISK"
sha = hashlib.sha256(out.read_bytes()).hexdigest()
(out.with_suffix(".zip.sha256")).write_text(f"{sha}  {out.name}\n")
print(f"Verified module: {out} ({out.stat().st_size:,} bytes)\nSHA256: {sha}")
