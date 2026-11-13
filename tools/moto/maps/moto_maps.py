#!/usr/bin/env python3
"""Replace the OM Indonesia mwms with per-province mwms for faster long car routes.

Steps (run in order, see README.md):
  tools      build Release generator tools and the Python venv (once)
  plan       download the OSM extract and split the OM regions into provinces (and parts)
  generate   generate the mwms of the plan
  apply      write the plan's borders, countries, names and packed polygons into data/
  apk        build the fdroid release APK with the new data/
  release    collect the mwms and the APK for a GitHub release
  clean      delete the large intermediate files
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
DATA = REPO / "data"
OM = HERE / "om"
BUILD = REPO / "build-moto-release"
TOOLS = ["generator_tool", "mwm_diff_tool", "world_roads_builder_tool"]
COUNTRY = "Indonesia"
# generate --batch: province ISO 3166-2 suffixes per island group.
ISLANDS = {
    "sumatra": ["AC", "SU", "SB", "RI", "KR", "JA", "SS", "BB", "BE", "LA"],
    "jawa": ["JK", "BT", "JB", "JT", "YO", "JI"],
    "nusa-tenggara": ["BA", "NB", "NT"],
    "kalimantan": ["KB", "KT", "KS", "KI", "KU"],
    "sulawesi": ["SA", "ST", "SN", "SG", "GO", "SR"],
    "maluku": ["MA", "MU"],
    "papua": ["PA", "PB", "PD", "PT", "PE", "PS"],
}
# Packed polygons are inflated (degrees, ~200 m): pieces cut by straight lines have bounding rects that only
# touch, and the router would not treat them as neighbours.
PACKED_INFLATE_DEG = 0.002


def run(cmd, **kw):
    print("+", " ".join(str(c) for c in cmd), flush=True)
    subprocess.run([str(c) for c in cmd], check=True, **kw)


def log(msg):
    print(msg, flush=True)


def config():
    return json.loads((HERE / "indonesia.json").read_text())


def work_dir(args):
    return Path(args.work).expanduser().resolve()


def venv_python(work):
    return work / "venv" / "bin" / "python"


def ensure_venv(work):
    """Re-runs this script with the venv interpreter (shapely, numpy, osmium)."""
    py = venv_python(work)
    if not py.exists():
        sys.exit(f"No venv in {work}. Run: {sys.argv[0]} tools")
    if Path(sys.prefix).resolve() != (work / "venv").resolve():
        os.execv(str(py), [str(py), *sys.argv])


def load_plan(work):
    path = work / "plan" / "plan.json"
    if not path.exists():
        sys.exit(f"{path} not found. Run: {sys.argv[0]} plan")
    return json.loads(path.read_text(encoding="utf-8"))


def pbf_path(work):
    return work / "pbf" / config()["pbf_url"].rsplit("/", 1)[1]


# --- .poly files --------------------------------------------------------------------------------


def read_geometry(path):
    from shapely.geometry import Polygon
    from shapely.ops import unary_union

    lines = Path(path).read_text().splitlines()
    outers, holes, i = [], [], 1
    while i < len(lines):
        header = lines[i].strip()
        i += 1
        if header in ("", "END"):
            continue
        ring = []
        while lines[i].strip() != "END":
            x, y = map(float, lines[i].split())
            ring.append((x, y))
            i += 1
        i += 1
        (holes if header.startswith("!") else outers).append(Polygon(ring))
    geom = unary_union(outers).buffer(0)
    return geom.difference(unary_union(holes)) if holes else geom


def write_poly(path, name, geom):
    from shapely.geometry import Polygon

    parts = [g for g in getattr(geom, "geoms", [geom]) if isinstance(g, Polygon) and not g.is_empty]
    lines, k = [name], 0
    for part in parts:
        for ring, hole in [(part.exterior, False)] + [(r, True) for r in part.interiors]:
            k += 1
            lines.append(f"!{k}" if hole else str(k))
            lines += [f"\t{x:.7f}\t{y:.7f}" for x, y in ring.coords]
            lines.append("END")
    lines.append("END")
    Path(path).write_text("\n".join(lines) + "\n")


PRIVATE_H = REPO / "private.h"
TAG_RE = re.compile(r'^#define MOTO_MAPS_TAG "([^"]*)"$', re.M)
BUILD_GRADLE = REPO / "android/app/build.gradle"
VERSION_RE = re.compile(r"findProperty\('motoVersion'\) \?: '([^']+)'")


def release_tag():
    """GitHub release tag of the app version in build.gradle, e.g. v1.7.2."""
    found = VERSION_RE.search(BUILD_GRADLE.read_text())
    if not found:
        sys.exit(f"motoVersion not found in {BUILD_GRADLE}")
    return f"v{found.group(1)}"


def maps_tag():
    """Release tag the app downloads the Indonesia maps from."""
    found = TAG_RE.search(PRIVATE_H.read_text())
    if not found:
        sys.exit(f"MOTO_MAPS_TAG not found in {PRIVATE_H}")
    return found.group(1)


def set_maps_tag(tag):
    text = PRIVATE_H.read_text()
    PRIVATE_H.write_text(TAG_RE.sub(f'#define MOTO_MAPS_TAG "{tag}"', text, count=1))


# --- data/ updates ------------------------------------------------------------------------------


def find_node(node, node_id):
    if node.get("id") == node_id:
        return node
    for child in node.get("g", []):
        found = find_node(child, node_id)
        if found:
            return found
    return None


def replaced_ids(data, plan):
    """OM regions of the plan plus pieces of a previous apply that replaced them."""
    doc = json.loads((data / "countries.json").read_text(encoding="utf-8"))
    group = find_node(doc, COUNTRY)
    ids = set(plan["om_regions"])
    ids |= {c["id"] for c in group["g"] if set(c.get("old", [])) & set(plan["om_regions"])}
    return ids


def update_data(data, plan, sizes, generated_borders):
    """Replaces the planned OM regions by the plan pieces in a data/ directory."""
    removed = replaced_ids(data, plan)
    pieces = plan["pieces"]

    for rid in removed:
        poly = data / "borders" / f"{rid}.poly"
        if poly.exists():
            poly.unlink()
    for p in pieces:
        shutil.copy(generated_borders / f"{p['id']}.poly", data / "borders" / f"{p['id']}.poly")

    path = data / "hierarchy.txt"
    lines = path.read_text(encoding="utf-8").split("\n")
    at = next(i for i, l in enumerate(lines) if l.split(";")[0] == COUNTRY) + 1
    lines = [l for l in lines if l.strip().split(";")[0] not in removed]
    lines[at:at] = [f" {p['id']};{p['wikidata']}" for p in pieces]
    path.write_text("\n".join(lines), encoding="utf-8")

    path = data / "countries.json"
    doc = json.loads(path.read_text(encoding="utf-8"))
    group = find_node(doc, COUNTRY)
    kept = [c for c in group["g"] if c["id"] not in removed]
    new = []
    for p in pieces:
        size = sizes.get(p["id"], {"s": 0, "h": ""})
        new.append({"id": p["id"], "old": [p["old"]], "affiliations": [COUNTRY, p["province"]],
                    "s": size["s"], "h": size["h"]})
    group["g"] = kept + new
    path.write_text(json.dumps(doc, indent=1, ensure_ascii=False), encoding="utf-8")
    return removed


def update_names(plan, removed):
    """Adds display names of the pieces; OM region names stay for outdated maps."""
    om = set(plan["om_regions"])
    for lang, key in (("id", "name_id"), ("en", "name_en")):
        path = DATA / "countries-strings" / f"{lang}.json" / "localize.json"
        lines = path.read_text(encoding="utf-8").split("\n")

        def key_of(line):
            return line.split('"')[1] if line.startswith('"') else None

        drop = (removed - om) | {p["id"] for p in plan["pieces"]}
        lines = [l for l in lines if key_of(l) not in drop and (key_of(l) or "").removesuffix(" Description") not in drop]
        at = next(i for i, l in enumerate(lines) if key_of(l) == COUNTRY)
        sep = lines[at][len(COUNTRY) + 2:].split('"')[0]
        lines[at + 1:at + 1] = [f'"{p["id"]}"{sep}"{p[key]}",' for p in plan["pieces"]]
        text = "\n".join(lines)
        json.loads(text)
        path.write_text(text, encoding="utf-8")


def pack_polygons(work, plan, data):
    """Rebuilds packed_polygons.bin of a data/ directory from its borders. The generator also maps points
    to countries with it, so it must match countries.json."""
    from shapely.geometry import JOIN_STYLE

    with tempfile.TemporaryDirectory(dir=work) as tmp:
        borders = Path(tmp) / "borders"
        shutil.copytree(data / "borders", borders)
        for p in plan["pieces"]:
            poly = borders / f"{p['id']}.poly"
            write_poly(poly, p["id"], read_geometry(poly).buffer(PACKED_INFLATE_DEG, join_style=JOIN_STYLE.mitre))
        run([BUILD / "generator_tool", f"--data_path={tmp}", f"--user_resource_path={data}",
             "--generate_packed_borders"], stdout=subprocess.DEVNULL)
        shutil.copy(Path(tmp) / "packed_polygons.bin", data / "packed_polygons.bin")


# --- commands -----------------------------------------------------------------------------------


def cmd_tools(args):
    work = work_dir(args)
    work.mkdir(parents=True, exist_ok=True)
    if not (BUILD / "build.ninja").exists():
        run(["cmake", "--preset", "release", "-B", BUILD], cwd=REPO)
    run(["cmake", "--build", BUILD, "--target", *TOOLS], cwd=REPO)
    if not venv_python(work).exists():
        run([sys.executable, "-m", "venv", work / "venv"])
    run([venv_python(work), "-m", "pip", "install", "-q", "-r",
         REPO / "tools/python/maps_generator/requirements_dev.txt", "shapely>=2.1", "osmium>=4"],
        cwd=REPO / "tools/python/maps_generator")


def fetch_pbf(work, refresh):
    pbf = pbf_path(work)
    pbf.parent.mkdir(parents=True, exist_ok=True)
    if refresh or not pbf.exists():
        run(["curl", "-L", "--fail", "-o", pbf, config()["pbf_url"]])
    return pbf


def file_hash(path, algo):
    h = hashlib.new(algo)
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 24), b""):
            h.update(chunk)
    return h.hexdigest()


def write_md5(pbf):
    """The generator checks the extract against <pbf>.md5."""
    md5 = Path(f"{pbf}.md5")
    if not md5.exists() or md5.stat().st_mtime < pbf.stat().st_mtime:
        md5.write_text(f"{file_hash(pbf, 'md5')}  {pbf.name}\n")


def cmd_plan(args):
    work = work_dir(args)
    ensure_venv(work)
    sys.path.insert(0, str(HERE))
    import plan as planner

    cfg = config()
    pbf = Path(args.pbf).resolve() if args.pbf else fetch_pbf(work, args.refresh_pbf)
    only = set(args.regions.split(",")) if args.regions else None
    limits = {k: getattr(args, k) or cfg[k] for k in ("max_mb", "min_km", "max_km", "min_mb")}
    plan = planner.make_plan(pbf, OM, work / "plan", cfg["iso_prefix"], limits, only, read_geometry, write_poly, log)
    plan["pbf"] = str(pbf)
    (work / "plan" / "plan.json").write_text(json.dumps(plan, indent=1, ensure_ascii=False), encoding="utf-8")
    log(f"\n{'id':40} {'old':26} {'est MB':>7} {'km':>5}  name")
    for p in plan["pieces"]:
        log(f"{p['id']:40} {p['old']:26} {p['est_mb']:7.1f} {p['extent_km']:5}  {p['name_id']}")
    log(f"\n{len(plan['pieces'])} pieces. Map: {work / 'plan' / 'plan.geojson'} (e.g. geojson.io)")
    log(f"Next: {sys.argv[0]} generate")


def write_ini(work, pbf):
    ini = work / "map_generator.ini"
    ini.write_text(f"""[Developer]
OMIM_PATH: {REPO}
BUILD_PATH: {BUILD}

[Main]
DEBUG: 0
MAIN_OUT_PATH: {work / 'build'}

[Generator tool]
USER_RESOURCE_PATH: {work / 'resources'}
THREADS_COUNT_FEATURES_STAGE: 0

[Osm tools]
OSM_TOOLS_PATH: {work / 'osmctools'}
OSM_TOOLS_SRC_PATH: ${{Developer:OMIM_PATH}}/tools/osmctools

[Logging]
LOG_FILE_PATH: ${{Main:MAIN_OUT_PATH}}/generation.log

[External]
PLANET_URL: file://{pbf}
PLANET_MD5_URL: ${{External:PLANET_URL}}.md5
NEED_BUILD_WORLD_ROADS: false

[Stages]
NEED_PLANET_UPDATE: 0
DATA_ARCHIVE_DIR: ${{Generator tool:USER_RESOURCE_PATH}}
DIFF_VERSION_DEPTH: 2

[Common]
THREADS_COUNT: 0

[Stats]
STATS_TYPES_CONFIG: ${{Developer:OMIM_PATH}}/tools/python/maps_generator/var/etc/stats_types_config.txt
""")
    return ini


def latest_build(work):
    builds = sorted(p for p in (work / "build").glob("20*") if p.is_dir())
    if not builds:
        sys.exit(f"No generator build in {work / 'build'}")
    return builds[-1]


def link_or_copy(src, dst_dir, name=None):
    dst = Path(dst_dir) / (name or Path(src).name)
    if dst.exists():
        dst.unlink()
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy(src, dst)


def generator_cmd(work, ini, pieces, order, skip, extra=()):
    return [venv_python(work), "-m", "maps_generator", "--config", ini,
            "--countries=" + ",".join(p["id"] for p in pieces), "--skip=" + ",".join(skip), f"--order={order}",
            *extra]


def cmd_generate(args):
    work = work_dir(args)
    ensure_venv(work)
    missing = [t for t in TOOLS if not (BUILD / t).exists()]
    if missing:
        sys.exit(f"Missing {missing} in {BUILD}. Run: {sys.argv[0]} tools")
    plan = load_plan(work)
    pieces = sorted(plan["pieces"], key=lambda p: -p["est_mb"])
    batch = pieces
    if args.batch:
        isos = ISLANDS[args.batch]
        batch = [p for p in pieces if p["iso"] in isos]
    pbf = Path(plan["pbf"])
    if args.refresh_pbf:
        if pbf != pbf_path(work):
            sys.exit(f"The plan uses {pbf}; replace that file yourself")
        fetch_pbf(work, True)
        # Features of the old extract must not be reused by the next batches.
        if (work / "build").exists():
            shutil.rmtree(work / "build")
    write_md5(pbf)
    order = work / "order.txt"
    order.write_text("".join(f"{p['id']}\t{int(p['est_mb'] * 10) + 1}\n" for p in pieces))
    ini = write_ini(work, pbf)
    skip = ["Coastline", "MwmDiffs"]
    # Marks a build whose Features stage ran for all pieces; batches then only run the per-mwm stages.
    features = work / "build" / "features_done"

    def prepare_resources():
        resources = work / "resources"
        if resources.exists():
            shutil.rmtree(resources)
        shutil.copytree(DATA, resources)
        update_data(resources, plan, {}, work / "plan" / "borders")
        pack_polygons(work, plan, resources)
        # A fresh Features run starts a new build: drop failed or old ones, they take several GB.
        if (work / "build").exists():
            shutil.rmtree(work / "build")

    if not args.batch:
        if not args.resume:
            prepare_resources()
        extra = ["-c", latest_build(work).name] if args.resume else []
        run(generator_cmd(work, ini, pieces, order, skip, extra), cwd=REPO / "tools/python")
    else:
        if not features.exists():
            if not args.resume:
                prepare_resources()
            extra = ["-c", latest_build(work).name] if args.resume else []
            run(generator_cmd(work, ini, pieces, order, skip + ["Mwm", "CountriesTxt", "Statistics", "Cleanup"],
                              extra), cwd=REPO / "tools/python")
            features.write_text(latest_build(work).name)
        build = features.read_text().strip()
        # Cleanup deletes intermediate files that later batches still need; "clean" removes them at the end.
        extra = ["-c", build] if args.resume else ["-c", build, "--from_stage", "Mwm"]
        run(generator_cmd(work, ini, batch, order, skip + ["Cleanup"], extra), cwd=REPO / "tools/python")

    version = next(p for p in latest_build(work).iterdir() if p.is_dir() and p.name.isdigit())
    maps = work / "maps"
    maps.mkdir(exist_ok=True)
    for p in batch:
        link_or_copy(version / f"{p['id']}.mwm", maps)
    link_or_copy(version / "countries.json", maps)
    done = {p["id"] for p in pieces if (maps / f"{p['id']}.mwm").exists()}
    size = sum((maps / f"{p['id']}.mwm").stat().st_size for p in batch)
    log(f"\nDone: {len(batch)} mwms ({size / 1e6:.0f} MB) in {maps}")
    todo = sorted({name for name, isos in ISLANDS.items() for p in pieces if p["iso"] in isos and p["id"] not in done})
    if todo:
        log(f"Batches left: {', '.join(todo)}  ({sys.argv[0]} generate --batch <name>)")
    else:
        log(f"All {len(pieces)} mwms generated. Next: {sys.argv[0]} apply   (then {sys.argv[0]} clean)")


def cmd_apply(args):
    work = work_dir(args)
    tag = release_tag()
    if subprocess.run(["git", "rev-parse", "-q", "--verify", f"refs/tags/{tag}"], cwd=REPO,
                      capture_output=True).returncode == 0:
        sys.exit(f"{tag} is already released. Bump motoVersion in {BUILD_GRADLE} first: new maps need a new release.")
    ensure_venv(work)
    plan = load_plan(work)
    generated = work / "maps" / "countries.json"
    if not generated.exists():
        sys.exit(f"{generated} not found. Run: {sys.argv[0]} generate")
    sizes = {}

    def collect(node):
        if "s" in node:
            sizes[node["id"]] = {"s": node["s"], "h": node["h"]}
        for child in node.get("g", []):
            collect(child)

    generated_doc = json.loads(generated.read_text(encoding="utf-8"))
    collect(generated_doc)
    missing = [p["id"] for p in plan["pieces"] if p["id"] not in sizes]
    if missing:
        sys.exit(f"{missing} not found in {generated}")
    removed = update_data(DATA, plan, sizes, work / "plan" / "borders")
    update_names(plan, removed)
    pack_polygons(work, plan, DATA)
    # The maps ship with the next app release; older apps keep downloading the maps of their own release.
    tag = release_tag()
    set_maps_tag(tag)
    log(f"\nUpdated data/ and MOTO_MAPS_TAG ({tag}) in private.h (review: git status data/ private.h).")
    log(f"Next: {sys.argv[0]} apk")


def cmd_apk(args):
    run(["./gradlew", "assembleFdroidRelease", "-Parm64", "-q"], cwd=REPO / "android")
    apk = max((REPO / "android/app/build/outputs/apk/fdroid/release").glob("*.apk"), key=lambda p: p.stat().st_mtime)
    log(f"APK: {apk}\nNext: {sys.argv[0]} release")


def cmd_release(args):
    work = work_dir(args)
    apks = list((REPO / "android/app/build/outputs/apk/fdroid/release").glob("*.apk"))
    if not apks:
        sys.exit(f"No APK. Run: {sys.argv[0]} apk")
    apk = max(apks, key=lambda p: p.stat().st_mtime)
    newest = max(p.stat().st_mtime for p in (DATA / "countries.json", PRIVATE_H, BUILD_GRADLE))
    if apk.stat().st_mtime < newest:
        sys.exit(f"{apk.name} is older than data/countries.json, private.h or build.gradle. Run: {sys.argv[0]} apk")
    tag = release_tag()
    # A release carries the maps only when this app version introduced them (MOTO_MAPS_TAG); later app-only
    # releases keep downloading them from that release.
    files = [apk]
    if maps_tag() == tag:
        plan = load_plan(work)
        files += [work / "maps" / f"{p['id']}.mwm" for p in plan["pieces"]]
    out = work / "release" / tag
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    link_or_copy(apk, out, f"MotoNav-{tag}-arm64.apk")
    for f in files[1:]:
        link_or_copy(f, out)
    sums = [f"{file_hash(f, 'sha256')}  {f.name}" for f in sorted(out.iterdir())]
    (out / "SHA256SUMS").write_text("\n".join(sums) + "\n")
    total = sum(f.stat().st_size for f in out.iterdir())
    maps_note = "with the Indonesia maps" if len(files) > 1 else f"APK only, maps stay in {maps_tag()}"
    log(f"Release files ({total / 1e6:.0f} MB, {maps_note}) in {out}")
    log(f"APK SHA-256 (for the release notes): {file_hash(out / f'MotoNav-{tag}-arm64.apk', 'sha256')}")
    # Never replace or delete the maps of a published release: apps built for it check the downloaded
    # size against their own countries.json.
    log(f"Publish with: gh release create {tag} {out}/*")


def cmd_clean(args):
    work = work_dir(args)
    targets = [work / "build", work / "resources"]
    if args.pbf:
        targets += [pbf_path(work), Path(f"{pbf_path(work)}.md5"), work / "osm_cache.pkl"]
    for t in targets:
        if t.is_dir():
            shutil.rmtree(t)
        elif t.exists():
            t.unlink()
        else:
            continue
        log(f"deleted {t}")
    log(f"Kept {work / 'maps'} and {work / 'plan'}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--work", default=os.environ.get("MOTO_MAPS_WORK", "~/moto-maps"),
                   help="Work directory for downloads and builds (default: ~/moto-maps or $MOTO_MAPS_WORK)")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("tools").set_defaults(fn=cmd_tools)
    pl = sub.add_parser("plan")
    pl.add_argument("--pbf", help="Use this OSM extract instead of downloading pbf_url")
    pl.add_argument("--refresh-pbf", action="store_true", help="Download the OSM extract again")
    pl.add_argument("--regions", help="Only these OM regions, comma separated (for testing)")
    pl.add_argument("--max-mb", type=float, help="Cut provinces estimated above this mwm size")
    pl.add_argument("--min-km", type=float, help="Never cut provinces smaller than this")
    pl.add_argument("--max-km", type=float, help="Cut provinces whose land is longer than this")
    pl.add_argument("--min-mb", type=float, help="...unless their estimated mwm is below this")
    pl.set_defaults(fn=cmd_plan)
    g = sub.add_parser("generate")
    g.add_argument("--batch", choices=sorted(ISLANDS), help="Only the pieces of this island group; the first "
                   "batch also prepares the OSM features of all pieces")
    g.add_argument("--resume", action="store_true", help="Continue the last build from its failed stage")
    g.add_argument("--refresh-pbf", action="store_true",
                   help="Download a fresh OSM extract first; the plan (province borders) stays the same")
    g.set_defaults(fn=cmd_generate)
    sub.add_parser("apply").set_defaults(fn=cmd_apply)
    sub.add_parser("apk").set_defaults(fn=cmd_apk)
    sub.add_parser("release").set_defaults(fn=cmd_release)
    c = sub.add_parser("clean")
    c.add_argument("--pbf", action="store_true", help="Also delete the downloaded OSM extract")
    c.set_defaults(fn=cmd_clean)
    args = p.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
