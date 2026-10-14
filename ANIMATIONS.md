# Animation support

Every selectable custom character includes a matching model and all five clips: idle,
walk, melee, ranged and magic. Esdeath and Rory retain their original assets. The seven
imported characters use Esdeath's timing and body choreography, adapted to their source rigs.

Models without all five clips, or with an incompatible vertex count, loop mode or hand-anchor
layout, are rejected by the production loader and development preview. The original game
thrall stays visible when a replacement cannot load. Resource tests check every selection,
geometry limits, movement, attack recovery, hand anchors, manifest hashes and source credits.

Gohan, Goku, Sanji, Shinji and Tatsumaki have mapped finger closure and melee tightening.
Hisoka and Saitama have fixed fists. Grip anchors use palm joints or the weighted hand surface,
calibrated in the ready pose. Sanji's already bent fingers use a smaller curl. No retained import
has separately weighted hair bones, so no imported hair or cloth motion is claimed.
Weapon grips, foot placement and NPC-triggered transitions still need in-game review.
This PR remains a draft until that review is complete.

## Regenerating imports

`tools/animation_profiles.json` contains the seven supported bone mappings.
`tools/retargeted_poses.py` adapts the original attack keys in `tools/girl_poses.py` and the
idle/walk motion from `tools/export_girl.py`. The exporter preserves skin weights and stable
vertex ordering, with mesh reduction before the armature modifier. `tools/rig_details.py`
calibrates hands and applies the explicitly mapped secondary chains. Tiny source triangles
are retained even when integer rounding makes them flat in one pose. Sanji and Shinji bake at
800 units instead of 200 for finer finger geometry; normal runtime height scaling compensates.
Body-motion translations remain expressed in the original 200-unit choreography space. Replace geometry and
all five clips together after inspecting the result.

```sh
blender -b --factory-startup --disable-autoexec --python-exit-code 1 --python tools/export_rigged_v2.py -- gohan goku hisoka saitama sanji shinji tatsumaki --source-root <custom-thralls-directory> --output-root <review-directory>
```

The source directory supplies the reviewed downloads in `assets/models` and source credits
in `assets/v2`. Exporting never installs resources automatically. Source models are not
required to build the plugin or run its bundled development preview.

## Deferred candidates

Experimental/static models and Kakashi's basic clips are excluded from this PR. They are
preserved locally under the Git-ignored `archives/` directory with checksums, credits,
the original manifest and review notes. The raw downloads remain in the source workspace.
An archive is local storage, not a remote backup. A candidate must pass the same geometry,
five-clip, attribution and visual review before being added back.

Frieren is deferred because the downloaded head also renders with missing eyes and a red forehead
band before conversion. Her new two-chain hair-sway trial, finger mapping, source credits and
replacement research are preserved locally in the grip-review archive. No suitable equivalent
replacement was verified for this update.
