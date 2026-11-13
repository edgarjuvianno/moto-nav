# Per-province Indonesia maps

Organic Maps ships Indonesia as 7 big mwms. A long car route inside one of them runs a full A* search
("Joints" mode), which takes minutes on a head unit. These tools replace them with one mwm per province
(OSM `admin_level=4`), and cut big provinces further with straight lines through sparse areas, so the
router can use the precomputed cross-mwm "leaps" (see `kCloseMwmPointsDistanceM` in
`libs/routing/index_router.cpp`).

```bash
tools/moto/maps/moto_maps.py tools      # once: Release generator tools + venv
tools/moto/maps/moto_maps.py plan       # download Indonesia OSM extract, split into pieces (~15 min)
tools/moto/maps/moto_maps.py generate   # generate all piece mwms (hours, see below)
tools/moto/maps/moto_maps.py apply      # write borders/countries/names/packed polygons into data/
tools/moto/maps/moto_maps.py apk        # build the app with the new data/
tools/moto/maps/moto_maps.py release    # mwms + APK + SHA256SUMS in one folder
tools/moto/maps/moto_maps.py clean      # delete the large intermediate files
```

`plan` prints the pieces with their estimated size and writes `plan/plan.geojson` (open it in
geojson.io to check the cuts). A province is cut while its estimated mwm is above `max_mb` and its
land is longer than `min_km`, or its land is longer than `max_km` and its estimated mwm is above
`min_mb` (long routes between two adjacent mwms still run in Joints mode). Tune them in
`indonesia.json` or with `--max-mb`, `--min-km`, `--max-km`, `--min-mb`; the OSM data is cached,
so a re-plan takes seconds. `generate --resume` continues a failed build.

The app downloads `Indonesia_*` mwms from the GitHub release tagged `MOTO_MAPS_TAG` (`private.h`);
other countries still come from the OM servers. `apply` sets the tag to `maps-<YYMMDD of the map build>`,
so every map build is a new release with its APK. Never delete or replace the assets of a published tag:
older apps download from it and check the file sizes against their own `countries.json`. Since all
Indonesia maps keep the OM data version, the app marks them outdated when their size differs from
`countries.json`, and the usual "Update" downloads the new build. A release with only a new APK keeps
the tag and needs no map upload.

Commit `data/` after `apply`; the APK must be built from it, and its `countries.json` must match
the released mwms. Old OM Indonesia maps on a device keep working for display and search; the app
offers the pieces as their update (`"old"` in `countries.json`).

The work directory defaults to `~/moto-maps` (`--work` or `$MOTO_MAPS_WORK`):

| Path | Size | |
|---|---|---|
| `pbf/` | ~2 GB (estimate) | OSM extract, delete with `clean --pbf` |
| `build/` | ~15 GB (estimate; 7 GB for Java alone) | Deleted by `clean` |
| `resources/` | ~0.5 GB | Deleted by `clean` |
| `plan/`, `maps/` | ~1.5 GB | The plan and the generated mwms |
| `release/<tag>/` | hard links | Upload with `gh release create <tag> release/<tag>/*` |

`om/` keeps the original OM Indonesia borders and sizes, so `plan` can run again after `apply` removed
them from `data/`. Packed polygons of the pieces are inflated by ~200 m: pieces cut by straight lines
have bounding rects that only touch, and the router would not treat them as neighbours.
