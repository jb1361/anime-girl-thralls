# Animation support

Every selectable custom character includes a matching model and all five clips: idle,
walk, melee, ranged and magic. Esdeath and Rory retain their original assets. The eight
imported characters use Esdeath's timing and body choreography, adapted to their source rigs.

Models without all five clips, or with an incompatible vertex count, loop mode or hand-anchor
layout, are rejected by the production loader and development preview. The original game
thrall stays visible when a replacement cannot load. Resource tests check every selection,
geometry limits, movement, attack recovery, hand anchors, manifest hashes and source credits.

The imported motions do not include separately animated fingers, hair or cloth. Their wrist
anchors are approximations; weapon grips, foot placement and NPC-triggered transitions still
need in-game review. This PR remains a draft until that review is complete.

## Regenerating imports

`tools/animation_profiles.json` contains the eight supported bone mappings.
`tools/retargeted_poses.py` adapts the original attack keys in `tools/girl_poses.py` and the
idle/walk motion from `tools/export_girl.py`. The exporter preserves skin weights and stable
vertex ordering, with mesh reduction before the armature modifier. Replace geometry and
all five clips together after inspecting the result.

```sh
blender -b --factory-startup --disable-autoexec --python-exit-code 1 --python tools/export_rigged_v2.py -- frieren gohan goku hisoka saitama sanji shinji tatsumaki --source-root <custom-thralls-directory> --output-root <review-directory>
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
