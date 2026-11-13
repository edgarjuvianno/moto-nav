# Per-province Indonesia maps

Organic Maps ships Indonesia as 7 big mwms. A long car route inside one of them runs a full A* search
("Joints" mode), which takes minutes on a head unit. These tools replace them with one mwm per province
(OSM `admin_level=4`), and cut big provinces further with straight lines through sparse areas, so the
router can use the precomputed cross-mwm "leaps" (see `kCloseMwmPointsDistanceM` in
`libs/routing/index_router.cpp`).

```bash
tools/moto/maps/moto_maps.py tools      # once: Release generator tools + venv
tools/moto/maps/moto_maps.py plan       # download Indonesia OSM extract, split into pieces (~15 min)
tools/moto/maps/moto_maps.py generate --batch <island>   # generate the mwms island by island
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
other countries still come from the OM servers. New maps ship with a new app release: bump `motoVersion`
in `android/app/build.gradle` first, then `apply` sets the tag to that version (e.g. `v1.7.2`) and
`release` puts the maps next to the APK. Later app-only releases keep the tag, so `release` adds only the
APK. Never delete or replace the maps of a published release: older apps download from it and check the
file sizes against their own `countries.json`. Since all Indonesia maps keep the OM data version, the app
marks them outdated when their size differs from `countries.json`, and the usual "Update" downloads them.

Commit `data/` after `apply`; the APK must be built from it, and its `countries.json` must match
the released mwms. Old OM Indonesia maps on a device keep working for display and search; the app
offers the pieces as their update (`"old"` in `countries.json`).

## Updating the maps

Fresh OSM data with the same province borders, released as the next app version (e.g. 1.7.3):

1. Generate the maps. Only the first batch downloads the new extract; `generate` lists the batches
   left after each run, `--resume` continues a failed one. Jawa takes the longest.
   ```bash
   caffeinate -i tools/moto/maps/moto_maps.py generate --batch maluku --refresh-pbf
   caffeinate -i tools/moto/maps/moto_maps.py generate --batch jawa   # then sumatra, sulawesi,
                                                                       # nusa-tenggara, kalimantan, papua
   ```
2. Bump `motoVersion` in `android/app/build.gradle` (`apply` refuses a version that is already tagged).
3. Apply and check that only `android/app/build.gradle`, `data/countries.json` and `private.h`
   changed. Changed `data/borders`, `hierarchy.txt` or `packed_polygons.bin` mean the plan changed:
   commit all of `data/` then.
   ```bash
   tools/moto/maps/moto_maps.py apply
   git status --short
   ```
4. Commit and push, so the release tag points at it.
   ```bash
   git add android/app/build.gradle data/countries.json private.h
   git commit -s -m "Bump version to 1.7.3 with fresh Indonesia maps"
   git push
   ```
5. Build and publish; `release` must say "with the Indonesia maps".
   ```bash
   tools/moto/maps/moto_maps.py apk
   tools/moto/maps/moto_maps.py release
   gh release create v1.7.3 ~/Documents/private-projects/moto-nav-release/v1.7.3/*
   ```
6. Free the disk: `tools/moto/maps/moto_maps.py clean` (`--pbf` also deletes the OSM extract).

After installing the new APK, riders see their Indonesia maps as "Update"; until then the old maps keep
working.

## App-only release

Bump `motoVersion`, commit, push, then `apk` and `release`. `MOTO_MAPS_TAG` stays at the release that
introduced the maps, so `release` packs only the APK and the app keeps downloading the maps from there.

The work directory, temporary generator files included, defaults to
`~/Documents/private-projects/moto-nav-maps` (`--work` or `$MOTO_MAPS_WORK`):

| Path | Size | |
|---|---|---|
| `pbf/` | ~2 GB (estimate) | OSM extract, delete with `clean --pbf` |
| `build/` | ~15 GB (estimate; 7 GB for Java alone) | Deleted by `clean` |
| `resources/` | ~0.5 GB | Deleted by `clean` |
| `plan/`, `maps/` | ~1.5 GB | The plan and the generated mwms |

`release` puts the files to upload in `~/Documents/private-projects/moto-nav-release/<tag>/`
(`--release-dir` or `$MOTO_RELEASE_DIR` to change it); delete them once uploaded.

`om/` keeps the original OM Indonesia borders and sizes, so `plan` can run again after `apply` removed
them from `data/`. Packed polygons of the pieces are inflated by ~200 m: pieces cut by straight lines
have bounding rects that only touch, and the router would not treat them as neighbours.
