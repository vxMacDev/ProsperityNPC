"""Package the reviewed source overlay locally; never installs anything."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

BASE_SHA = "a6a43ab319c62e360d5b8d720756111398a34aefc5277d973f369253944e484a"
CLASSES = {
    "NpcTransactionGuard", "commands/DeleteCommand", "gui/Gui", "gui/Gui$FlagSetter",
    "gui/Gui$TeleportToRegionClickAction", "minifeatures/selloffer/Offer",
    "regions/CountdownRegion", "regions/Region", "regions/Region$ActionReason",
    "regions/RegionManager", "regions/RegionManager$DummyChunk", "regions/RegionManager$UpdateScheduler",
}
EXPECTED = {"net/alex9849/arm/" + name + ".class" for name in CLASSES}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("base", type=Path)
    p.add_argument("classes", type=Path)
    p.add_argument("output", type=Path)
    args = p.parse_args()
    if args.output.exists() or args.output.resolve() == args.base.resolve():
        raise SystemExit("Refusing to overwrite existing artifact")
    if sha(args.base.read_bytes()) != BASE_SHA:
        raise SystemExit("Base ARM jar hash mismatch")
    overlay = {f.relative_to(args.classes).as_posix(): f.read_bytes()
               for f in args.classes.rglob("*") if f.is_file()}
    if set(overlay) != EXPECTED:
        raise SystemExit("Compiled overlay differs from fixed class allowlist")
    with zipfile.ZipFile(args.base) as source:
        names = source.namelist()
        if len(names) != len(set(names)):
            raise SystemExit("Duplicate base entries")
        if any(n.upper().startswith("META-INF/") and n.upper().endswith((".SF", ".RSA", ".DSA")) for n in names):
            raise SystemExit("Signed base jar requires a separate signing plan")
        new_class = "net/alex9849/arm/NpcTransactionGuard.class"
        if EXPECTED - set(names) != {new_class}:
            raise SystemExit("Unexpected base class layout")
        # Keep the original plugin metadata; this explicit manifest identifies the fork.
        manifest = {
            "protocol": 1, "base_sha256": BASE_SHA,
            "upstream": "c728c755525226e1d2bfc51af7e5e3803d301c08",
            "classes": {n: sha(overlay[n]) for n in sorted(overlay)},
            "status": "DEV candidate; no live integration verification yet",
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.output, "x", compression=zipfile.ZIP_DEFLATED) as target:
            for info in source.infolist():
                target.writestr(info, overlay.get(info.filename, source.read(info)))
            target.writestr(new_class, overlay[new_class])
            target.writestr("META-INF/prosperity-ownership-overlay.json", json.dumps(manifest, sort_keys=True))
    with zipfile.ZipFile(args.base) as source, zipfile.ZipFile(args.output) as target:
        for n in names:
            expected = overlay[n] if n in overlay else source.read(n)
            if target.read(n) != expected:
                raise SystemExit("Packaged content mismatch: " + n)
        if target.testzip() is not None:
            raise SystemExit("Packaged jar CRC failure")
    print(json.dumps({"output": str(args.output), "sha256": sha(args.output.read_bytes()),
                      "replaced_classes": len(EXPECTED) - 1, "new_classes": 1,
                      "unchanged_original_entries": len(names) - len(EXPECTED) + 1}))


if __name__ == "__main__":
    main()
