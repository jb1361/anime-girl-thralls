# Anime Girl Thralls

A RuneLite plugin that replaces the Arceuus thrall models (ghost, skeleton and zombie, all tiers)
with a custom anime girl model. Currently ships with an Esdeath model.

## How it works

RuneLite has no API for swapping an NPC's model, so the plugin:

1. Hides the real thrall NPC through a `RenderCallback` registered with `RenderCallbackManager`.
2. Draws a `RuneLiteObject` with the custom model at the thrall's position and facing, updated every
   frame.

The custom geometry is built using the approach pioneered by
[creators-kit](https://github.com/ScreteMonge/creators-kit): tiny cache models are merged with
`Client#mergeModels` until the merged `ModelData` has exactly the right number of vertices and
faces, and its vertex, face and colour arrays are then overwritten in place.

## Model format

`src/main/resources/com/animegirlthralls/esdeath.json` is in the creators-kit Blender JSON format,
so it can also be loaded in creators-kit for previewing. Coordinates use 128 units per tile with
x east, z north and negative y up. Face colours are `[hue, luminance, saturation, alpha]` in the
0 to 1 range, decoded the same way creators-kit decodes them.

The client only renders about 3900 vertices and 6200 faces per model, so meshes must be decimated
to fit.

## Regenerating the model

`tools/export_rs_model.py` runs inside Blender. It decimates the mesh named `Body` to a face
budget, samples each face's texture at its UV centroid to produce flat face colours, drops fully
transparent (alpha cut-out) faces and writes the JSON plus front, side and back preview renders.

```
blender -b model.blend --python tools/export_rs_model.py -- <output dir> <target faces>
```

Pick the largest face budget that keeps the vertex count under 3900. The bundled model used a budget
of 4600 faces, giving 3816 vertices and 4552 faces.

## Configuration

- A model dropdown for each thrall type (ghost, skeleton, zombie): Original keeps the game's model,
  otherwise pick a girl. New girls are added as entries in `AnimeGirl.java` pointing at a JSON resource.
- Model height in game units (a player is roughly 180; default 190).
