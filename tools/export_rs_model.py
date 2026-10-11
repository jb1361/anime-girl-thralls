# Decimate the textured Esdeath mesh, bake texture colours per face, and write
# a creators-kit compatible BlenderModel JSON + a preview render.
import bpy, json, math, colorsys, sys, os
import numpy as np

OUT_DIR = sys.argv[sys.argv.index('--') + 1]
TARGET_FACES = int(sys.argv[sys.argv.index('--') + 2])
BRIGHTNESS = 0.8  # client palette gamma; stored = target ** (1/BRIGHTNESS)

obj = bpy.data.objects['Body']
src_faces = len(obj.data.polygons)
ratio = min(1.0, TARGET_FACES / src_faces)
mod = obj.modifiers.new('rs_decimate', 'DECIMATE')
mod.ratio = ratio
mod.use_collapse_triangulate = True
depsgraph = bpy.context.evaluated_depsgraph_get()
eo = obj.evaluated_get(depsgraph)
me = bpy.data.meshes.new_from_object(eo, preserve_all_data_layers=True, depsgraph=depsgraph)
me.calc_loop_triangles()
mw = obj.matrix_world

# --- material -> image / flat colour
img_cache = {}
def image_pixels(img):
    if img.name not in img_cache:
        w, h = img.size
        arr = np.empty(w * h * 4, dtype=np.float32)
        img.pixels.foreach_get(arr)
        img_cache[img.name] = (arr, w, h)
    return img_cache[img.name]

def material_source(mat):
    if mat is None or not mat.use_nodes:
        return None, (mat.diffuse_color[:3] if mat else (0.8, 0.8, 0.8))
    tree = mat.node_tree
    img_node = None
    flat = tuple(mat.diffuse_color[:3])
    for n in tree.nodes:
        if n.type == 'BSDF_PRINCIPLED':
            inp = n.inputs['Base Color']
            flat = tuple(inp.default_value[:3])
            if inp.is_linked:
                src = inp.links[0].from_node
                if src.type == 'TEX_IMAGE' and src.image:
                    img_node = src
    if img_node is None:
        for n in tree.nodes:
            if n.type == 'TEX_IMAGE' and n.image:
                img_node = n
                break
    return (img_node.image if img_node else None), flat

mat_sources = [material_source(s.material) for s in obj.material_slots]

def sample(img, u, v):
    arr, w, h = image_pixels(img)
    u = u - math.floor(u); v = v - math.floor(v)
    x = min(w - 1, int(u * w)); y = min(h - 1, int(v * h))
    i = (y * w + x) * 4
    return arr[i], arr[i + 1], arr[i + 2], arr[i + 3]

def to_jagex_hsl(rgb):
    r, g, b = [min(1.0, max(0.0, c)) ** (1.0 / BRIGHTNESS) for c in rgb]
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    hue = min(63, int(math.ceil(h * 64)))
    sat = min(7, int(math.ceil(s * 7)))
    lum = min(127, int(math.ceil(l * 127)))
    return hue, sat, lum

uv_layer = me.uv_layers.active.data
verts = []
for v in me.vertices:
    co = mw @ v.co
    verts.append([co.x, co.y, co.z])
floor = min(c[2] for c in verts)
rs_verts = [[int(round(c[0] * 128)), int(round(-(c[2] - floor) * 128)), int(round(c[1] * 128))] for c in verts]

faces = []
face_rgb = []
hsl_index = {}
face_colours = []
face_colour_index = []
for tri in me.loop_triangles:
    a, b, c = tri.vertices
    if a == b or b == c or a == c:
        continue
    poly = me.polygons[tri.polygon_index]
    img, flat = mat_sources[poly.material_index] if poly.material_index < len(mat_sources) else (None, (0.8, 0.8, 0.8))
    if img is not None:
        uvs = [uv_layer[l].uv for l in tri.loops]
        cu = sum(u.x for u in uvs) / 3; cv = sum(u.y for u in uvs) / 3
        pts = [(cu, cv)] + [((cu + u.x) / 2, (cv + u.y) / 2) for u in uvs]
        samples = [sample(img, u, v) for u, v in pts]
        opaque = [s for s in samples if s[3] > 0.1]
        if not opaque:
            continue  # fully transparent (alpha cut-out) face
        rgb = tuple(sum(s[k] for s in opaque) / len(opaque) for k in range(3))
    else:
        rgb = tuple(flat)
    hsl = to_jagex_hsl(rgb)
    if hsl not in hsl_index:
        hsl_index[hsl] = len(face_colours)
        H, S, L = hsl
        # creators-kit decodes: H = 63 - c0*63, L = c1*127, S = c2*7, alpha = c3
        face_colours.append([(63 - H - 0.5) / 63.0, (L + 0.5) / 127.0, (S + 0.5) / 7.0, 1.0])
    face_colour_index.append(hsl_index[hsl])
    faces.append([a, b, c])
    face_rgb.append(rgb)

data = {
    "useVertexColours": False,
    "vertices": rs_verts,
    "faces": faces,
    "vertexColours": [],
    "vertexColourIndex": [],
    "faceColours": face_colours,
    "faceColourIndex": face_colour_index,
    "priorities": [0] * len(faces),
    "clientTicks": [],
    "animVertices": [],
}
with open(os.path.join(OUT_DIR, 'esdeath.json'), 'w') as f:
    json.dump(data, f, separators=(',', ':'))

ys = [v[1] for v in rs_verts]; xs = [v[0] for v in rs_verts]; zs = [v[2] for v in rs_verts]
print("STATS src_faces", src_faces, "ratio", round(ratio, 4), "verts", len(rs_verts), "faces", len(faces), "colours", len(face_colours))
print("STATS x", min(xs), max(xs), "y", min(ys), max(ys), "z", min(zs), max(zs))

# --- preview render with baked face colours
pm = bpy.data.meshes.new('preview')
pm.from_pydata(verts, [], faces)
pm.update()
ca = pm.color_attributes.new('Col', 'BYTE_COLOR', 'CORNER')
cols = np.empty(len(pm.loops) * 4, dtype=np.float32)
for p in pm.polygons:
    r, g, b = face_rgb[p.index]
    for li in p.loop_indices:
        cols[li * 4:li * 4 + 4] = (r, g, b, 1.0)
ca.data.foreach_set('color', cols)
po = bpy.data.objects.new('preview', pm)
scene = bpy.context.scene
scene.collection.objects.link(po)
for o in list(scene.collection.all_objects):
    if o.name not in ('preview',):
        o.hide_render = True
height = max(c[2] for c in verts) - floor
cam = bpy.data.cameras.new('pc'); cam.type = 'ORTHO'; cam.ortho_scale = height * 1.15
scene.render.engine = 'BLENDER_WORKBENCH'
scene.display.shading.light = 'FLAT'
scene.display.shading.color_type = 'VERTEX'
scene.render.resolution_x = 600; scene.render.resolution_y = 900
scene.render.film_transparent = False
scene.world = scene.world or bpy.data.worlds.new('w')
scene.world.color = (0.2, 0.2, 0.25)
cx = (max(xs) + min(xs)) / 256.0; cy = (max(zs) + min(zs)) / 256.0; cz = floor + height / 2
for name, pos, rot in [('front', (cx, cy - 600, cz), (math.pi / 2, 0, 0)), ('side', (cx + 600, cy, cz), (math.pi / 2, 0, math.pi / 2)), ('back', (cx, cy + 600, cz), (math.pi / 2, 0, math.pi))]:
    co = bpy.data.objects.new('cam_' + name, cam)
    scene.collection.objects.link(co)
    co.location = pos; co.rotation_euler = rot
    scene.camera = co
    scene.render.filepath = os.path.join(OUT_DIR, 'preview_' + name + '.png')
    bpy.ops.render.render(write_still=True)
print("DONE")
