# Anime Girl Thralls

A RuneLite plugin that replaces the Arceuus thrall models (ghost, skeleton and zombie, all tiers)
with a custom anime girl model.

Nine imported characters include trial idle, walk, melee, ranged and magic clips for **Custom** mode:
Frieren, Saitama, Gohan, Goku, Hisoka, Kakashi, Sanji, Shinji and Tatsumaki. Their hand anchors allow
configured weapons; grip placement still needs in-game review. The other 32 imported models have
standing geometry: use **None**, or try **Mimic thrall**

## How it works

RuneLite has no API for swapping an NPC's model, so the plugin:

1. Hides the real thrall NPC through a `RenderCallback` registered with `RenderCallbackManager`.
2. Draws a `RuneLiteObject` with the custom model at the thrall's position and facing, updated every
   frame.

The custom geometry is built using the approach pioneered by
[creators-kit](https://github.com/ScreteMonge/creators-kit): tiny cache models are merged with
`Client#mergeModels` until the merged `ModelData` has exactly the right number of vertices and
faces, and its vertex, face and colour arrays are then overwritten in place.

## Animation

The custom model has no vertex groups, so the client cannot animate it. Instead the plugin ships
baked vertex animation: each clip stores every vertex position per keyframe and the plugin
interpolates between keyframes at draw time, writing the pose into the client's shared scratch model
(`Client#applyTransformations` with no animations) so the model's bounds are recomputed each draw.

The original two characters have five clips each, posed procedurally on her rig in Blender by `tools/export_girl.py` with
per-girl poses from `tools/girl_poses.py`. Esdeath's:

- `idle` (3 s loop): breathing, a slow look around, hair sway, forearms forward holding her weapon upright.
- `walk` (1.17 s loop): a stride with knee bend and arm swing.
- `attack_melee` (0.9 s): a right straight punch with a step, used for zombie thralls.
- `attack_ranged` (1.3 s): raise the bow, draw to the cheek, release, used for skeleton thralls.
- `attack_magic` (1.25 s): raise the staff and thrust it forward, used for ghost thralls.

Rory Mercury shares the idle and walk, holding her halberd upright at her side, and swings it in
her attacks: an overhead chop (melee, 1.08 s), a lunging thrust with the spike (ranged, 1.08 s) and
raising it high then slamming its butt into the ground (magic, 1.25 s).

The thrall only provides cues: the attack clip starts when the thrall's attack animation starts, the
walk clip plays while the thrall's pose animation is a walk, and the idle clip plays otherwise. Clip
switches blend over 0.15 s. Looping clips are offset per npc so several thralls do not move in unison.

The config also offers "Mimic thrall", which keeps a copy of the thrall's own model, lets the client
animate it, and moves each custom vertex by the weighted displacement of its nearest thrall vertices
(`WrapDeformer`), and "None" for the rest pose.

## Weapons

Each thrall kind can hold an item from the game, configured by item id: a staff for ghost thralls
(default Staff of water), a bow for skeleton thralls (default Magic shortbow) and optionally a melee
weapon for zombie thralls. The item's inventory model is loaded from the game cache and merged into
the girl's model when a replacement is built, then moved every draw to follow a hand. Characters modelled
with their own weapon (Rory and her halberd) never get the configured item.

Every clip stores a transform for each hand (`VertexClip` anchors): origin in the middle of the fist,
x along the knuckle row (up when the thumb is up), y from wrist to knuckles, z = x cross y. The original
exporter derives these from finger bones. The imported-rig exporter instead uses approximate
wrist-local frames, which still require in-game grip review.

Items are aligned into that frame from their shape alone (`WeaponAlign`), so no per-item tuning is
needed: the long axis from a principal axis fit goes along x, a bow's belly faces +y (string toward
the archer), a staff's heavier end points up, and a melee weapon is gripped near its lighter end.

Clip files (`<girl>_<clip>.bin`) are little endian: magic `AGTC`, version u8 (2), fps u8, loop u8,
anchor count u8, vertex count u16, frame count u16, then frames x vertices x (x, y, z) as int16 in
the model's JSON coordinates, then frames x anchors x (px, py, pz, qw, qx, qy, qz) as float32.

## Model format

`src/main/resources/com/animegirlthralls/esdeath.json` is in the creators-kit Blender JSON format,
so it can also be loaded in creators-kit for previewing. Coordinates use 128 units per tile with
x east, z north and negative y up. Face colours are `[hue, luminance, saturation, alpha]` in the
0 to 1 range, decoded the same way creators-kit decodes them.

The client only renders about 3900 vertices and 6200 faces per model, so meshes must be decimated
to fit.

## Regenerating the model and clips

`tools/export_girl.py` runs inside Blender against a rigged source file: an armature named
`Armature` with MMD style bone names (Hips, Spine, Chest, Neck, Head, Left arm, Left elbow, Left wrist,
three-joint fingers, Left leg, Left knee, Left ankle and the Right equivalents) and a mesh named
`Body` deformed by it. It puts a Decimate modifier before the Armature modifier so every pose shares
one topology, poses the rig procedurally for each clip, bakes the base model (idle frame 0) with
per-face colours sampled from the textures (or the materials' flat colours), drops fully transparent
(alpha cut-out) faces, writes the model JSON and the clip files, and renders preview strips of each
clip.

```
blender -b esdeath2.blend --python tools/export_girl.py -- esdeath <output dir> 4600 [weapon obj dir]
blender -b rory_rigged.blend --python tools/export_girl.py -- rory <output dir> 6000
```

Poses in `girl_poses.py` rotate bones about armature axes. Besides `_curl` (finger curl) and
`_level` (turn a hand so its weapon axis points up), a pose can use `_aim_l` / `_aim_r` to turn a
hand so its weapon axis points along a world direction, which is how Rory's halberd is steered.

With a directory of item models as OBJ files (861.obj, 1383.obj) the preview renders show the
weapons in her hands. `src/test/java/.../tools/ItemModelDump.java` dumps those from the local game
cache: `AGT_DUMP_ITEMS=861,1383 AGT_DUMP_OUT=<dir> ./gradlew test --tests '*ItemModelDump*'`.

Pick the largest face budget that keeps the vertex count under 3900. Esdeath used a budget of 4600
faces, giving 3818 vertices and 4551 faces.

### Rory's rig

Rory's source, `rory.blend`, is an untextured, unrigged STL of a statue: she stands on one leg on a
rock base with her halberd raised. `tools/rig_rory.py` turns it into `rory_rigged.blend`:

1. Splits the statue into its print pieces, drops the base, voxel remeshes and decimates each piece
   to its share of the face budget, and colours each piece flat (with a few regions such as bare
   thighs and gloves picked by position).
2. Places an armature with the Esdeath bone names at the statue's joints (measured from gridded
   renders) and weights each piece to the bones that may move it; the halberd follows her right wrist.
3. Reposes her into Esdeath's A-pose bone directions, scales her to Esdeath's height and saves that
   as the rest pose. The right forearm is turned so the halberd stands upright, butt on the floor,
   in her base pose.

```
blender -b rory.blend --python tools/rig_rory.py -- <out dir>/rory_rigged.blend 6000 [preview dir]
```

A budget of 6000 faces gives 3018 vertices and 5984 faces. Her halberd rises above her head, so her
body (feet to top of head) is 0.747 of the model's height, the body fraction in `AnimeGirl.java`.

## Configuration

- A model dropdown for each thrall type (ghost, skeleton, zombie): Original keeps the game's model,
  otherwise pick a girl (defaults: Esdeath for ghost and skeleton, Rory for zombie). New girls are
  added as entries in `AnimeGirl.java` pointing at a JSON resource, with the fraction of the model's
  height that is her body.
- Model height in game units (a player is roughly 180; default 210), measured feet to top of head.
- Animation: Custom (default), Mimic thrall, or None.
- Weapon item ids for ghost, skeleton and zombie thralls (-1 for none).

## Credits and licensing

The plugin's code is released under the BSD 2-Clause License (see `LICENSE`). The character
models are not covered by that license. They are fan art of characters owned by their respective
rights holders, bundled here for free, non-commercial use only. No endorsement by the rights
holders is implied, and the plugin is not sold or monetised in any way.

- Esdeath from *Akame ga Kill!* © Takahiro, Tetsuya Tashiro / SQUARE ENIX
  ([Square Enix](https://www.square-enix.com/)); anime by White Fox.
- Rory Mercury from *GATE: Jieitai Kanochi nite, Kaku Tatakaeri* © Takumi Yanai, AlphaPolis /
  GATE Production Committee ([AlphaPolis](https://www.alphapolis.co.jp/)); anime by A-1 Pictures.

Rights holders who want a character removed can contact the repository owner directly through
GitHub, and the content will be taken down promptly. GitHub's own
[DMCA takedown process](https://docs.github.com/en/site-policy/content-removal-policies/dmca-takedown-policy)
is also available for anything hosted in this repository.
