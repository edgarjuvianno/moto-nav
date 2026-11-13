"""Plans how the OM Indonesia mwms are split: one piece per province (OSM admin_level=4), and large
provinces are cut further with straight lines through sparse areas.

Runs inside the tools venv (shapely, numpy, osmium).
"""

import json
import math
import pickle
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import osmium
import shapely
from shapely.geometry import LineString, Polygon, box, mapping
from shapely.ops import linemerge, polygonize, unary_union

# Density grid step in degrees (~2.2 km).
GRID = 0.02
# Shared province borders are simplified with this tolerance (degrees, ~50 m); OM outer borders are kept.
SIMPLIFY_DEG = 0.0005
# A cut must leave at least this share of the piece's weight on each side.
MIN_CUT_SHARE = 0.3
# A province gets a piece in an OM region only if at least this share of its OSM nodes lies there.
MIN_PROVINCE_SHARE = 0.1
# Bump when the cached OSM reading changes.
CACHE_VERSION = 2
PLACE_RANK = {"city": 2, "town": 1}
KM_PER_DEG = 111.32

# ISO 3166-2 suffix -> (Indonesian name, English name).
PROVINCES = {
    "AC": ("Aceh", "Aceh"),
    "SU": ("Sumatera Utara", "North Sumatra"),
    "SB": ("Sumatera Barat", "West Sumatra"),
    "RI": ("Riau", "Riau"),
    "KR": ("Kepulauan Riau", "Riau Islands"),
    "JA": ("Jambi", "Jambi"),
    "SS": ("Sumatera Selatan", "South Sumatra"),
    "BB": ("Kepulauan Bangka Belitung", "Bangka Belitung Islands"),
    "BE": ("Bengkulu", "Bengkulu"),
    "LA": ("Lampung", "Lampung"),
    "JK": ("DKI Jakarta", "Jakarta"),
    "BT": ("Banten", "Banten"),
    "JB": ("Jawa Barat", "West Java"),
    "JT": ("Jawa Tengah", "Central Java"),
    "YO": ("DI Yogyakarta", "Yogyakarta"),
    "JI": ("Jawa Timur", "East Java"),
    "BA": ("Bali", "Bali"),
    "NB": ("Nusa Tenggara Barat", "West Nusa Tenggara"),
    "NT": ("Nusa Tenggara Timur", "East Nusa Tenggara"),
    "KB": ("Kalimantan Barat", "West Kalimantan"),
    "KT": ("Kalimantan Tengah", "Central Kalimantan"),
    "KS": ("Kalimantan Selatan", "South Kalimantan"),
    "KI": ("Kalimantan Timur", "East Kalimantan"),
    "KU": ("Kalimantan Utara", "North Kalimantan"),
    "SA": ("Sulawesi Utara", "North Sulawesi"),
    "ST": ("Sulawesi Tengah", "Central Sulawesi"),
    "SN": ("Sulawesi Selatan", "South Sulawesi"),
    "SG": ("Sulawesi Tenggara", "Southeast Sulawesi"),
    "GO": ("Gorontalo", "Gorontalo"),
    "SR": ("Sulawesi Barat", "West Sulawesi"),
    "MA": ("Maluku", "Maluku"),
    "MU": ("Maluku Utara", "North Maluku"),
    "PA": ("Papua", "Papua"),
    "PB": ("Papua Barat", "West Papua"),
    "PD": ("Papua Barat Daya", "Southwest Papua"),
    "PT": ("Papua Tengah", "Central Papua"),
    "PE": ("Papua Pegunungan", "Highland Papua"),
    "PS": ("Papua Selatan", "South Papua"),
}

COMPASS = {
    "N": ("Utara", "North"), "S": ("Selatan", "South"), "W": ("Barat", "West"), "E": ("Timur", "East"),
    "NW": ("Barat Laut", "North-West"), "NE": ("Timur Laut", "North-East"),
    "SW": ("Barat Daya", "South-West"), "SE": ("Tenggara", "South-East"),
}


@dataclass
class Province:
    iso: str
    name_id: str
    name_en: str
    wikidata: str
    outer: list = field(default_factory=list)  # way ids
    inner: list = field(default_factory=list)
    geom: object = None
    weight: float = 0.0


@dataclass
class Piece:
    id: str
    om: str
    province: Province
    geom: object
    label: tuple = ("", "")  # compass suffix (id, en)
    weight: float = 0.0
    est_mb: float = 0.0


# --- OSM reading --------------------------------------------------------------------------------


def read_provinces(pbf, iso_prefix, log):
    provinces = {}
    for rel in osmium.FileProcessor(str(pbf), osmium.osm.RELATION):
        tags = rel.tags
        iso = tags.get("ISO3166-2", "")
        if tags.get("boundary") != "administrative" or tags.get("admin_level") != "4" or not iso.startswith(iso_prefix):
            continue
        code = iso[len(iso_prefix):]
        name_id, name_en = PROVINCES.get(code, (tags.get("name", code), tags.get("name:en", tags.get("name", code))))
        p = Province(code, name_id, name_en, tags.get("wikidata", ""))
        for m in rel.members:
            if m.type == "w":
                (p.inner if m.role == "inner" else p.outer).append(m.ref)
        provinces[code] = p
    unknown = sorted(set(provinces) - set(PROVINCES))
    if unknown:
        log(f"warning: provinces without a name in PROVINCES: {unknown}")
    missing = sorted(set(PROVINCES) - set(provinces))
    if missing:
        log(f"note: provinces not in this extract: {missing}")

    way_ids = {w for p in provinces.values() for w in p.outer + p.inner}
    ways = {}
    for w in osmium.FileProcessor(str(pbf), osmium.osm.WAY).with_filter(osmium.filter.IdFilter(way_ids)):
        ways[w.id] = [n.ref for n in w.nodes]
    return provinces, ways


def read_nodes(pbf, node_ids, bounds):
    """Returns coordinates of |node_ids|, a node count grid over |bounds| and cities/towns as
    (rank, population, name, lon, lat)."""
    minx, miny, maxx, maxy = bounds
    nx, ny = int(math.ceil((maxx - minx) / GRID)), int(math.ceil((maxy - miny) / GRID))
    grid = np.zeros((ny, nx), dtype=np.int32)
    coords, places = {}, []
    batch_x, batch_y = [], []

    def flush():
        if batch_x:
            xs = ((np.array(batch_x) - minx) / GRID).astype(np.int64)
            ys = ((np.array(batch_y) - miny) / GRID).astype(np.int64)
            ok = (xs >= 0) & (xs < nx) & (ys >= 0) & (ys < ny)
            np.add.at(grid, (ys[ok], xs[ok]), 1)
            batch_x.clear()
            batch_y.clear()

    for n in osmium.FileProcessor(str(pbf), osmium.osm.NODE):
        loc = n.location
        if not loc.valid():
            continue
        batch_x.append(loc.lon)
        batch_y.append(loc.lat)
        if n.id in node_ids:
            coords[n.id] = (loc.lon, loc.lat)
        if n.tags:
            rank = PLACE_RANK.get(n.tags.get("place"))
            if rank and "name" in n.tags:
                pop = n.tags.get("population", "0").replace(".", "").replace(",", "")
                places.append((rank, int(pop) if pop.isdigit() else 0, n.tags["name"], loc.lon, loc.lat))
        if len(batch_x) >= 1_000_000:
            flush()
    flush()
    return coords, grid, places


def assemble(p, ways, coords, log):
    def rings(way_ids):
        lines = []
        for w in way_ids:
            pts = [coords[n] for n in ways.get(w, []) if n in coords]
            if len(pts) >= 2:
                lines.append(LineString(pts))
        if not lines:
            return []
        merged = linemerge(lines)
        merged = list(getattr(merged, "geoms", [merged]))
        polys = list(polygonize(merged))
        gaps = [l for l in merged if not l.is_ring]
        if gaps:
            # Boundary parts at sea can lie outside the extract: join the loose ends with straight lines.
            log(f"note: {p.name_id}: closing {len(gaps)} open boundary parts")
            polys.append(shapely.make_valid(Polygon(chain(gaps))))
        return polys

    outer = rings(p.outer)
    if not outer:
        log(f"warning: {p.name_id}: boundary is incomplete in this extract, skipped")
        return None
    geom = unary_union(outer)
    inner = rings(p.inner)
    if inner:
        geom = geom.difference(unary_union(inner))
    return shapely.make_valid(geom)


def chain(lines):
    """Joins open lines into one ring, always continuing with the nearest loose end."""
    rest = [list(l.coords) for l in lines]
    ring = rest.pop(0)
    while rest:
        end = ring[-1]

        def gap(pts):
            return min(math.dist(end, pts[0]), math.dist(end, pts[-1]))

        nxt = rest.pop(min(range(len(rest)), key=lambda i: gap(rest[i])))
        ring += nxt if math.dist(end, nxt[0]) <= math.dist(end, nxt[-1]) else nxt[::-1]
    return ring + ring[:1]


# --- partition ----------------------------------------------------------------------------------


def polygons(g):
    out = []
    for x in getattr(g, "geoms", [g]):
        if isinstance(x, Polygon) and not x.is_empty:
            out.append(x)
        elif hasattr(x, "geoms"):
            out += polygons(x)
    return out


def polygonal(g):
    """Drops lines and points that overlays leave on shared borders."""
    return unary_union(polygons(g))


def partition(region, provinces, density):
    """Splits |region| between |provinces|; parts covered by no province go to the best neighbour."""
    parts, used = [], None
    for p in provinces:
        if p.geom is None or not p.geom.intersects(region):
            continue
        g = p.geom.intersection(region)
        if used is not None:
            g = g.difference(used)
        g = polygonal(g)
        # OM region borders do not follow province borders: slivers of neighbour provinces join the rest.
        share = density.weight(g) / p.weight if p.weight else g.area / p.geom.area
        if share < MIN_PROVINCE_SHARE:
            continue
        parts.append([p, g])
        used = g if used is None else used.union(g)
    if not parts:
        return []
    rest = region.difference(used)
    for hole in polygons(rest):
        shared = [hole.boundary.intersection(g.boundary).length for _, g in parts]
        best = max(range(len(parts)), key=lambda i: shared[i]) if max(shared) > 0 else \
            min(range(len(parts)), key=lambda i: hole.distance(parts[i][1]))
        parts[best][1] = polygonal(parts[best][1].union(hole))
    geoms = [polygonal(g) for g in shapely.set_precision([g for _, g in parts], 1e-7)]
    simplified = shapely.coverage_simplify(geoms, SIMPLIFY_DEG, simplify_boundary=False)
    if all(s.is_valid and not s.is_empty for s in simplified):
        geoms = simplified
    return [(p, g) for (p, _), g in zip(parts, geoms)]


# --- weights and cuts ---------------------------------------------------------------------------


class Density:
    def __init__(self, grid, bounds):
        self.grid, (self.minx, self.miny, _, _) = grid, bounds

    def cells(self, geom):
        """Returns (ix, iy, count) of grid cells whose centre lies in |geom|."""
        minx, miny, maxx, maxy = geom.bounds
        x0, y0 = max(int((minx - self.minx) / GRID), 0), max(int((miny - self.miny) / GRID), 0)
        x1 = min(int((maxx - self.minx) / GRID) + 1, self.grid.shape[1])
        y1 = min(int((maxy - self.miny) / GRID) + 1, self.grid.shape[0])
        ix, iy = np.meshgrid(np.arange(x0, x1), np.arange(y0, y1))
        cx, cy = self.minx + (ix + 0.5) * GRID, self.miny + (iy + 0.5) * GRID
        inside = shapely.contains_xy(geom, cx, cy)
        return ix[inside], iy[inside], self.grid[iy[inside], ix[inside]]

    def weight(self, geom):
        return float(self.cells(geom)[2].sum())

    def land_extent_km(self, geom):
        """Longest side of the box around cells with OSM nodes (OM regions include a lot of sea)."""
        ix, iy, w = self.cells(geom)
        ix, iy = ix[w > 0], iy[w > 0]
        if len(ix) == 0:
            return 0.0
        lat = math.radians(self.miny + (iy.min() + iy.max() + 1) / 2 * GRID)
        return max((ix.max() - ix.min() + 1) * math.cos(lat), iy.max() - iy.min() + 1) * GRID * KM_PER_DEG

    def best_cut(self, geom):
        """Returns (axis, coordinate) of a straight cut across the longer side of the land that balances
        both weight and land, through the sparsest line."""
        ix, iy, w = self.cells(geom)
        land = w > 0
        ix, iy, w = ix[land], iy[land], w[land]
        lat = math.radians(self.miny + (iy.min() + iy.max() + 1) / 2 * GRID)
        axis = "x" if (ix.max() - ix.min()) * math.cos(lat) >= iy.max() - iy.min() else "y"
        idx = ix if axis == "x" else iy
        lo = idx.min()
        profile = np.bincount(idx - lo, weights=w)
        share = np.cumsum(profile) / profile.sum()
        land_share = np.cumsum(np.bincount(idx - lo)) / len(idx)

        def between(x):
            return (x >= MIN_CUT_SHARE) & (x <= 1 - MIN_CUT_SHARE)

        ok = np.where(between(share) & between(land_share))[0]
        if len(ok) == 0:
            ok = np.where(between(share))[0]
        if len(ok) == 0:
            ok = np.array([int(np.searchsorted(share, 0.5))])
        # Cut after column k: the line runs between columns k and k+1, weigh both.
        cost = profile[ok] + profile[np.minimum(ok + 1, len(profile) - 1)]
        k = int(ok[np.argmin(cost)])
        origin = self.minx if axis == "x" else self.miny
        return axis, origin + (lo + k + 1) * GRID


def split(piece_geom, density, mb_per_weight, limits, path=()):
    """Recursively cuts a geometry that is too big or too long; yields (geometry, compass path)."""
    est = density.weight(piece_geom) * mb_per_weight
    km = density.land_extent_km(piece_geom)
    big = est > limits["max_mb"] and km > limits["min_km"]
    long = km > limits["max_km"] and est > limits["min_mb"]
    if not big and not long:
        yield piece_geom, path
        return
    axis, c = density.best_cut(piece_geom)
    minx, miny, maxx, maxy = piece_geom.bounds
    low = box(minx - 1, miny - 1, c, maxy + 1) if axis == "x" else box(minx - 1, miny - 1, maxx + 1, c)
    a, b = polygonal(piece_geom.intersection(low)), polygonal(piece_geom.difference(low))
    if a.is_empty or b.is_empty:
        yield piece_geom, path
        return
    sides = ("W", "E") if axis == "x" else ("S", "N")
    yield from split(a, density, mb_per_weight, limits, path + (sides[0],))
    yield from split(b, density, mb_per_weight, limits, path + (sides[1],))


def compass(path):
    ns = next((d for d in reversed(path) if d in "NS"), "")
    we = next((d for d in reversed(path) if d in "WE"), "")
    return ns + we


# --- main ---------------------------------------------------------------------------------------


def read_osm(pbf, iso_prefix, bounds, cache, log):
    """Provinces with geometries, the node density grid and places, cached next to the plan."""
    stat = Path(pbf).stat()
    key = (CACHE_VERSION, str(pbf), stat.st_size, stat.st_mtime, iso_prefix, bounds)
    if cache.exists():
        with open(cache, "rb") as f:
            data = pickle.load(f)
        if data[0] == key:
            log("using cached OSM data")
            return data[1:]
    log("reading province relations and ways...")
    provinces, ways = read_provinces(pbf, iso_prefix, log)
    node_ids = {n for nodes in ways.values() for n in nodes}
    log(f"reading nodes ({len(provinces)} provinces, {len(node_ids)} boundary nodes)...")
    coords, grid, places = read_nodes(pbf, node_ids, bounds)
    for p in provinces.values():
        p.geom = assemble(p, ways, coords, log)
    cache.parent.mkdir(parents=True, exist_ok=True)
    with open(cache, "wb") as f:
        pickle.dump((key, provinces, grid, places), f)
    return provinces, grid, places


def make_plan(pbf, om_dir, out_dir, iso_prefix, limits, only_regions, read_geometry, write_poly, log):
    regions = json.loads((om_dir / "regions.json").read_text(encoding="utf-8"))
    if only_regions:
        regions = [r for r in regions if r["id"] in only_regions]
    region_geoms = {r["id"]: read_geometry(om_dir / f"{r['id']}.poly") for r in regions}

    all_bounds = unary_union([read_geometry(f) for f in om_dir.glob("*.poly")]).bounds
    provinces, grid, places = read_osm(pbf, iso_prefix, all_bounds, out_dir.parent / "osm_cache.pkl", log)
    density = Density(grid, all_bounds)
    for p in provinces.values():
        p.weight = density.weight(p.geom) if p.geom is not None else 0.0

    pieces = []
    for r in regions:
        region = region_geoms[r["id"]]
        mb_per_weight = r["s"] / 1e6 / max(density.weight(region), 1.0)
        parts = partition(region, sorted(provinces.values(), key=lambda p: p.iso), density)
        if not parts:
            log(f"warning: no province of this extract lies in {r['id']}, kept as is")
            continue
        for prov, geom in parts:
            cuts = list(split(geom, density, mb_per_weight, limits))
            for g, path in cuts:
                piece = Piece("", r["id"], prov, g)
                piece.weight = density.weight(g)
                piece.est_mb = piece.weight * mb_per_weight
                if len(cuts) > 1:
                    piece.label = label(g, path, places)
                pieces.append(piece)

    assign_ids(pieces)
    borders = out_dir / "borders"
    borders.mkdir(parents=True, exist_ok=True)
    for old in borders.glob("*.poly"):
        old.unlink()
    plan = {"om_regions": [r["id"] for r in regions], "pieces": []}
    features = []
    for p in pieces:
        write_poly(borders / f"{p.id}.poly", p.id, p.geom)
        suffix_id, suffix_en = p.label
        entry = {"id": p.id, "old": p.om,
                 "name_id": p.province.name_id + (f" — {suffix_id}" if suffix_id else ""),
                 "name_en": p.province.name_en + (f" — {suffix_en}" if suffix_en else ""),
                 "province": p.province.name_id, "iso": p.province.iso, "wikidata": p.province.wikidata,
                 "est_mb": round(p.est_mb, 1), "extent_km": round(density.land_extent_km(p.geom))}
        plan["pieces"].append(entry)
        features.append({"type": "Feature", "properties": entry, "geometry": mapping(p.geom)})
    (out_dir / "plan.geojson").write_text(json.dumps({"type": "FeatureCollection", "features": features}),
                                          encoding="utf-8")
    return plan


def label(geom, path, places):
    """Names a cut piece after its largest city or town, else by compass direction."""
    inside = [pl for pl in places if shapely.contains_xy(geom, pl[3], pl[4])]
    if inside:
        name = max(inside, key=lambda pl: (pl[0], pl[1]))[2]
        return name, name
    return COMPASS.get(compass(path), ("", ""))


def assign_ids(pieces):
    """Ids without spaces (GitHub release assets): Indonesia_<Province>[_<n>], unique over all pieces."""
    by_prov = {}
    for p in pieces:
        by_prov.setdefault(p.province.name_id, []).append(p)
    for name, group in by_prov.items():
        base = "Indonesia_" + name.replace(" ", "_")
        if len(group) == 1:
            group[0].id = base
            group[0].label = ("", "")
            continue
        labels = [g.label for g in group]
        for n, g in enumerate(group, 1):
            g.id = f"{base}_{n}"
            if not g.label[0] or labels.count(g.label) > 1:
                g.label = (f"{g.label[0]} {n}".strip(), f"{g.label[1]} {n}".strip())
