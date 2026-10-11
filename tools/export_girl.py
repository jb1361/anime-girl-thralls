# Pose a girl's rig procedurally, bake the clips through the decimate pipeline and write the base
# model JSON (creators-kit format), binary vertex clips with hand anchors, and preview renders.
#
#   blender -b esdeath2.blend --python export_girl.py -- esdeath <out dir> <target faces> [weapon obj dir]
#   blender -b rory_rigged.blend --python export_girl.py -- rory <out dir> <target faces>
#
# The blend needs an armature named Armature with the MMD style bone names used below and a mesh named
# Body deformed by it. Per-girl poses (base pose and attack keys) live in girl_poses.py.
#
# Clip format v2 (little endian): magic "AGTC", version u8 = 2, fps u8, loop u8, anchor count u8,
# vertex count u16, frame count u16, then frames x vertices x (x, y, z) int16, then
# frames x anchors x (px, py, pz, qw, qx, qy, qz) float32. Anchor 0 is the left hand, 1 the right hand.
#
# Hand frame: x = fist axis (little finger -> index finger, "up" when the thumb is up), y = wrist ->
# knuckles, z = x cross y, origin in the middle of the fist. Weapons are placed with their long axis
# along x, so a bow or staff stands upright whenever the thumb points up.
import bpy, sys, os, math, json, struct, colorsys, zlib
import numpy as np
from mathutils import Matrix, Vector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import girl_poses

argv = sys.argv[sys.argv.index('--') + 1:]
GIRL = argv[0]
OUT_DIR = argv[1]
TARGET_FACES = int(argv[2])
WEAPON_DIR = argv[3] if len(argv) > 3 else None
BRIGHTNESS = 0.8
REFERENCE_HEIGHT = 190  # in-game model height at which weapons are 1:1 game size

arm = bpy.data.objects['Armature']
body = bpy.data.objects['Body']
ARM_SCALE = arm.scale.x

# ---------------------------------------------------------------- modifiers: decimate BEFORE armature
src_faces = len(body.data.polygons)
if src_faces > TARGET_FACES:
    dec = body.modifiers.new('rs_decimate', 'DECIMATE')
    dec.ratio = TARGET_FACES / src_faces
    dec.use_collapse_triangulate = True
    body.modifiers.move(len(body.modifiers) - 1, 0)
    assert body.modifiers[0].type == 'DECIMATE', [m.type for m in body.modifiers]

# ---------------------------------------------------------------- posing helpers
bones = arm.data.bones
pose = arm.pose.bones
rest_rot = {b.name: b.matrix_local.to_3x3() for b in bones}


def set_bone(name, x=0.0, y=0.0, z=0.0, loc=None):
    """Rotate a bone about armature-space axes (Y first, then X, then Z), angles in degrees."""
    pb = pose[name]
    R = rest_rot[name]
    Rw = (Matrix.Rotation(math.radians(z), 3, 'Z')
          @ Matrix.Rotation(math.radians(x), 3, 'X')
          @ Matrix.Rotation(math.radians(y), 3, 'Y'))
    pb.rotation_mode = 'QUATERNION'
    pb.rotation_quaternion = (R.inverted() @ Rw @ R).to_quaternion()
    pb.location = R.inverted() @ (Vector(loc) / ARM_SCALE) if loc is not None else Vector((0, 0, 0))


def reset_pose():
    for pb in pose:
        pb.rotation_mode = 'QUATERNION'
        pb.rotation_quaternion = (1, 0, 0, 0)
        pb.location = (0, 0, 0)
        pb.scale = (1, 1, 1)


def apply_pose(p):
    reset_pose()
    for name, v in p.items():
        if name in pose:
            set_bone(name, v.get('x', 0.0), v.get('y', 0.0), v.get('z', 0.0), v.get('loc'))
    bpy.context.view_layer.update()


def add(p, name, **kw):
    d = p.setdefault(name, {})
    for k, v in kw.items():
        if k == 'loc':
            old = d.get('loc', (0, 0, 0))
            d['loc'] = tuple(a + b for a, b in zip(old, v))
        else:
            d[k] = d.get(k, 0.0) + v


def phase(*names):
    # stable across runs, unlike hash() of a string
    return zlib.crc32('/'.join(names).encode()) % 7


def chain_bones():
    out = []
    for b in bones:
        n = b.name
        parts = n.split('_')
        if n.startswith(('HairFar_', 'Far2_', 'Far3_', 'Far5_')):
            out.append((n, int(parts[1]), phase(parts[0], parts[-1]), 1.0))
        elif n.startswith('Rambut_'):
            out.append((n, int(parts[2]), phase(parts[1]), 0.6))
        elif n.startswith('SkirtBones_'):
            out.append((n, int(parts[1]), int(parts[2]), 0.4))
    return out


CHAINS = chain_bones()


def sway(p, t, amp, period, extra_x=0.0):
    w = 2 * math.pi / period
    for name, depth, phase, k in CHAINS:
        ph = phase * 0.9 - depth * 0.6
        add(p, name, x=k * (amp * math.sin(w * t + ph) + extra_x), y=k * 0.5 * amp * math.cos(w * t + ph * 1.3))


# Hand frames from the finger bones, in world space
HANDS = [
    ('Left wrist', 'MiddleFinger1_L', 'IndexFinger1_L', 'LittleFinger1_L'),
    ('Right wrist', 'MiddleFinger1_R', 'IndexFinger1_R', 'LittleFinger1_R'),
]


def bone_head(name):
    return arm.matrix_world @ pose[name].head


HAND_ROLL = [0.0, 0.0]  # per hand correction so the weapon axis is vertical in the base pose


def raw_hand_frames():
    frames = []
    for wrist, middle, index, little in HANDS:
        w, m, i, l = bone_head(wrist), bone_head(middle), bone_head(index), bone_head(little)
        x = (i - l).normalized()
        y = (m - w)
        y = (y - y.dot(x) * x).normalized()
        z = x.cross(y)
        origin = w + 0.6 * (m - w)
        frames.append((origin, x, y, z))
    return frames


def hand_frames():
    frames = []
    for (origin, x, y, z), roll in zip(raw_hand_frames(), HAND_ROLL):
        c, s_ = math.cos(roll), math.sin(roll)
        frames.append((origin, (x * c + y * s_).normalized(), (y * c - x * s_).normalized(), z))
    return frames


def level_hands(weights):
    """Bend the wrist about the palm normal so the weapon axis (hand x) points up, per hand weight."""
    up = Vector((0, 0, 1))
    inv = arm.matrix_world.inverted().to_3x3()
    for _ in range(3):
        bpy.context.view_layer.update()
        frames = hand_frames()
        for h, wrist_name in enumerate(('Left wrist', 'Right wrist')):
            w = weights.get('l' if h == 0 else 'r', 0.0)
            if w <= 0.0:
                continue
            origin, x, y, z = frames[h]
            tilt = math.atan2(up.dot(y), up.dot(x)) * w
            if abs(tilt) < 1e-4:
                continue
            pb = pose[wrist_name]
            axis = (inv @ z).normalized()
            head = pb.matrix.to_translation()
            R = Matrix.Translation(head) @ Matrix.Rotation(tilt, 4, axis) @ Matrix.Translation(-head)
            pb.matrix = R @ pb.matrix
    bpy.context.view_layer.update()


def aim_hands(aims):
    """Turn each wrist so the weapon axis (hand x) points along a world direction, per hand weight."""
    inv = arm.matrix_world.inverted().to_3x3()
    for _ in range(3):
        bpy.context.view_layer.update()
        frames = hand_frames()
        for h, wrist_name in enumerate(('Left wrist', 'Right wrist')):
            aim = aims[h]
            if aim is None:
                continue
            target, w = aim
            origin, x, y, z = frames[h]
            axis = x.cross(target)
            if axis.length < 1e-6:
                continue
            angle = x.angle(target) * w
            pb = pose[wrist_name]
            head = pb.matrix.to_translation()
            R = Matrix.Translation(head) @ Matrix.Rotation(angle, 4, (inv @ axis).normalized()) @ Matrix.Translation(-head)
            pb.matrix = R @ pb.matrix
    bpy.context.view_layer.update()


FINGERS = ['IndexFinger', 'MiddleFinger', 'RingFinger', 'LittleFinger']


def curl_fingers(weights):
    """Curl the fingers toward the palm about the knuckle axis, degrees per joint and hand."""
    inv = arm.matrix_world.inverted().to_3x3()
    for h, side in enumerate(('L', 'R')):
        deg = weights.get('l' if h == 0 else 'r', 0.0)
        if abs(deg) < 1e-3:
            continue
        sign = -1.0 if h == 0 else 1.0
        bpy.context.view_layer.update()
        origin, a, f, z = raw_hand_frames()[h]
        axis = (inv @ a).normalized()
        for joint in (1, 2, 3):
            bpy.context.view_layer.update()
            for finger in FINGERS:
                name = f'{finger}{joint}_{side}'
                if name not in pose:
                    continue
                pb = pose[name]
                head = pb.matrix.to_translation()
                R = Matrix.Translation(head) @ Matrix.Rotation(math.radians(sign * deg), 4, axis) @ Matrix.Translation(-head)
                pb.matrix = R @ pb.matrix
    bpy.context.view_layer.update()


def apply_pose_full(p):
    apply_pose(p)
    curl = p.get('_curl')
    if curl:
        curl_fingers(curl)
    lv = p.get('_level')
    if lv:
        level_hands(lv)
    aims = []
    for side in ('l', 'r'):
        a = p.get('_aim_' + side)
        d = Vector((a.get('x', 0.0), a.get('y', 0.0), a.get('z', 0.0))) if a else None
        aims.append((d.normalized(), min(1.0, a.get('w', 0.0))) if d is not None and d.length > 1e-6 else None)
    if any(aims):
        aim_hands(aims)


def calibrate_hands():
    """Roll each hand frame in its x/y plane so x points straight up in the base pose."""
    apply_pose(base_pose())
    up = Vector((0, 0, 1))
    for h, (origin, x, y, z) in enumerate(raw_hand_frames()):
        HAND_ROLL[h] = math.atan2(up.dot(y), up.dot(x))
        print("HANDROLL", h, round(math.degrees(HAND_ROLL[h]), 1))


# ---------------------------------------------------------------- poses
def base_pose():
    return girl_poses.base_pose(GIRL)


calibrate_hands()

IDLE_PERIOD = 3.0


def idle_pose(t):
    p = base_pose()
    w = 2 * math.pi / IDLE_PERIOD
    br = math.sin(w * t)
    add(p, 'Chest', x=1.5 * br)
    add(p, 'Spine', x=0.5 * br)
    add(p, 'Hips', loc=(0, 0, 0.6 * br))
    add(p, 'Head', z=4.0 * math.sin(w * t + 1.0), x=1.5 * math.sin(w * t + 2.0))
    add(p, 'Left arm', y=2.0 * br)
    add(p, 'Right arm', y=-2.0 * br)
    add(p, 'Left elbow', x=3.0 * br)
    add(p, 'Right elbow', x=3.0 * br)
    sway(p, t, 2.0, IDLE_PERIOD)
    return p


WALK_PERIOD = 28 / 24.0


def walk_pose(t):
    p = base_pose()
    w = 2 * math.pi / WALK_PERIOD
    s = math.sin(w * t)
    c = math.cos(w * t)
    A, K = 40.0, 65.0
    add(p, 'Left leg', x=-A * s)
    add(p, 'Right leg', x=A * s)
    kl, kr = K * max(0.0, c), K * max(0.0, -c)
    add(p, 'Left knee', x=kl)
    add(p, 'Right knee', x=kr)
    add(p, 'Left ankle', x=-0.35 * kl)
    add(p, 'Right ankle', x=-0.35 * kr)
    add(p, 'Left arm', x=30.0 * s)
    add(p, 'Right arm', x=-30.0 * s)
    add(p, 'Left elbow', x=-8.0 * s)
    add(p, 'Right elbow', x=8.0 * s)
    add(p, 'Hips', loc=(0, 0, 1.4 * math.cos(2 * w * t)), z=7.0 * s)
    add(p, 'Chest', z=-8.0 * s, x=2.0 * math.cos(2 * w * t))
    add(p, 'Spine', x=5.0)
    add(p, 'Head', z=3.0 * s)
    sway(p, t, 4.0, WALK_PERIOD)
    return p


def smoothstep(u):
    u = min(1.0, max(0.0, u))
    return u * u * (3 - 2 * u)


def lerp_key(a, b, u):
    out = {}
    for name in set(a) | set(b):
        da, db = a.get(name, {}), b.get(name, {})
        d = {}
        for k in set(da) | set(db):
            zero = (0, 0, 0) if k == 'loc' else 0.0
            va, vb = da.get(k, zero), db.get(k, zero)
            d[k] = tuple(x + (y - x) * u for x, y in zip(va, vb)) if k == 'loc' else va + (vb - va) * u
        out[name] = d
    return out


def keyed_pose(keys, t, whip_center=None, whip_amp=0.0):
    f = t * 24.0
    key = keys[-1][1]
    for (f0, k0), (f1, k1) in zip(keys, keys[1:]):
        if f <= f1:
            key = lerp_key(k0, k1, smoothstep((f - f0) / (f1 - f0)))
            break
    p = base_pose()
    for name, d in key.items():
        add(p, name, **d)
    whip = -whip_amp * math.exp(-((f - whip_center) / 4.0) ** 2) if whip_center is not None else 0.0
    sway(p, t, 1.5, IDLE_PERIOD, extra_x=whip)
    return p


ATTACKS = girl_poses.ATTACKS[GIRL]


def attack_clip(name):
    keys, whip_center, whip_amp = ATTACKS[name]
    return (lambda t: keyed_pose(keys, t, whip_center=whip_center, whip_amp=whip_amp)), keys[-1][0] / 24.0


# ---------------------------------------------------------------- mesh evaluation
def evaluated_mesh():
    depsgraph = bpy.context.evaluated_depsgraph_get()
    eo = body.evaluated_get(depsgraph)
    return eo, eo.to_mesh()


def eval_vertices():
    eo, me = evaluated_mesh()
    n = len(me.vertices)
    co = np.empty(n * 3, dtype=np.float32)
    me.vertices.foreach_get('co', co)
    co = co.reshape(n, 3)
    M = np.array(eo.matrix_world, dtype=np.float32)
    world = co @ M[:3, :3].T + M[:3, 3]
    me.calc_loop_triangles()
    tris = np.empty(len(me.loop_triangles) * 3, dtype=np.int32)
    me.loop_triangles.foreach_get('vertices', tris)
    eo.to_mesh_clear()
    return world, tris


# ---------------------------------------------------------------- base model (idle frame 0)
apply_pose_full(idle_pose(0.0))
eo, me = evaluated_mesh()
me.calc_loop_triangles()
mw = eo.matrix_world

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
    img_node = None
    flat = tuple(mat.diffuse_color[:3])
    for n in mat.node_tree.nodes:
        if n.type == 'BSDF_PRINCIPLED':
            inp = n.inputs['Base Color']
            flat = tuple(inp.default_value[:3])
            if inp.is_linked and inp.links[0].from_node.type == 'TEX_IMAGE' and inp.links[0].from_node.image:
                img_node = inp.links[0].from_node
    if img_node is None:
        for n in mat.node_tree.nodes:
            if n.type == 'TEX_IMAGE' and n.image:
                img_node = n
                break
    return (img_node.image if img_node else None), flat


mat_sources = [material_source(s.material) for s in body.material_slots]


def sample(img, u, v):
    arr, w, h = image_pixels(img)
    u -= math.floor(u); v -= math.floor(v)
    x = min(w - 1, int(u * w)); y = min(h - 1, int(v * h))
    i = (y * w + x) * 4
    return arr[i], arr[i + 1], arr[i + 2], arr[i + 3]


def to_jagex_hsl(rgb):
    r, g, b = [min(1.0, max(0.0, c)) ** (1.0 / BRIGHTNESS) for c in rgb]
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    return min(63, int(math.ceil(h * 64))), min(7, int(math.ceil(s * 7))), min(127, int(math.ceil(l * 127)))


uv_layer = me.uv_layers.active.data if me.uv_layers.active else None
base_world = np.array([mw @ v.co for v in me.vertices], dtype=np.float32)
floor = float(base_world[:, 2].min())

faces, face_rgb, face_colours, face_colour_index = [], [], [], {}
palette = []
for tri in me.loop_triangles:
    a, b, c = tri.vertices
    if a == b or b == c or a == c:
        continue
    poly = me.polygons[tri.polygon_index]
    img, flat = mat_sources[poly.material_index] if poly.material_index < len(mat_sources) else (None, (0.8, 0.8, 0.8))
    if img is not None and uv_layer is not None:
        uvs = [uv_layer[l].uv for l in tri.loops]
        cu = sum(u.x for u in uvs) / 3; cv = sum(u.y for u in uvs) / 3
        pts = [(cu, cv)] + [((cu + u.x) / 2, (cv + u.y) / 2) for u in uvs]
        samples = [sample(img, u, v) for u, v in pts]
        opaque = [s for s in samples if s[3] > 0.1]
        if not opaque:
            continue
        rgb = tuple(sum(s[k] for s in opaque) / len(opaque) for k in range(3))
    else:
        rgb = tuple(flat)
    hsl = to_jagex_hsl(rgb)
    if hsl not in face_colour_index:
        face_colour_index[hsl] = len(palette)
        H, S, L = hsl
        palette.append([(63 - H - 0.5) / 63.0, (L + 0.5) / 127.0, (S + 0.5) / 7.0, 1.0])
    face_colours.append(face_colour_index[hsl])
    faces.append([int(a), int(b), int(c)])
    face_rgb.append(rgb)

base_tris = np.empty(len(me.loop_triangles) * 3, dtype=np.int32)
me.loop_triangles.foreach_get('vertices', base_tris)
eo.to_mesh_clear()
nverts = len(base_world)


def to_rs(world):
    rs = np.empty_like(world)
    rs[:, 0] = world[:, 0] * 128
    rs[:, 1] = -(world[:, 2] - floor) * 128
    rs[:, 2] = world[:, 1] * 128
    return np.rint(rs).astype(np.int32)


def dir_to_rs(v):
    return Vector((v.x, -v.z, v.y))


def anchors_rs():
    """Hand frames as (pos xyz in RS coords, quaternion wxyz) for the current pose."""
    out = []
    for origin, x, y, z in hand_frames():
        pos = to_rs(np.array([[origin.x, origin.y, origin.z]], dtype=np.float32))[0]
        cols = [dir_to_rs(x), dir_to_rs(y), dir_to_rs(z)]
        R = Matrix([[cols[0][r], cols[1][r], cols[2][r]] for r in range(3)])
        q = R.to_quaternion()
        out.append([float(pos[0]), float(pos[1]), float(pos[2]), q.w, q.x, q.y, q.z])
    return out


base_rs = to_rs(base_world)
data = {
    "useVertexColours": False,
    "vertices": base_rs.tolist(),
    "faces": faces,
    "vertexColours": [], "vertexColourIndex": [],
    "faceColours": palette, "faceColourIndex": face_colours,
    "priorities": [0] * len(faces), "clientTicks": [], "animVertices": [],
}
with open(os.path.join(OUT_DIR, f'{GIRL}.json'), 'w') as f:
    json.dump(data, f, separators=(',', ':'))
print("STATS base verts", nverts, "faces", len(faces), "colours", len(palette),
      "y", int(base_rs[:, 1].min()), int(base_rs[:, 1].max()))

# ---------------------------------------------------------------- clips
CLIPS = [
    ('idle', idle_pose, IDLE_PERIOD, 8, True),
    ('walk', walk_pose, WALK_PERIOD, 12, True),
] + [(name,) + attack_clip(name) + (12, False) for name in ('attack_melee', 'attack_ranged', 'attack_magic')]
preview = {}
for name, fn, duration, fps, loop in CLIPS:
    n = int(round(duration * fps)) + (0 if loop else 1)
    frames, anchors, hand_world = [], [], []
    for i in range(n):
        apply_pose_full(fn(i / fps))
        world, tris = eval_vertices()
        assert len(world) == nverts, (name, i, len(world), nverts)
        assert np.array_equal(tris, base_tris), ("topology changed", name, i)
        frames.append(to_rs(world))
        anchors.append(anchors_rs())
        hand_world.append(hand_frames())
    stacked = np.stack(frames)
    assert np.abs(stacked).max() <= 32767, ("clip exceeds int16 range", name, int(np.abs(stacked).max()))
    arr = stacked.astype(np.int16)
    anc = np.array(anchors, dtype=np.float32)
    assert arr.shape == (n, nverts, 3) and anc.shape == (n, 2, 7)
    with open(os.path.join(OUT_DIR, f'{GIRL}_{name}.bin'), 'wb') as f:
        f.write(struct.pack('<4sBBBBHH', b'AGTC', 2, fps, 1 if loop else 0, 2, nverts, n))
        f.write(arr.astype('<i2').tobytes())
        f.write(anc.astype('<f4').tobytes())
    idx = sorted({0, n // 4, n // 2, (3 * n) // 4})
    preview[name] = [(frames[j], hand_world[j]) for j in idx]
    print("CLIP", name, "frames", n, "fps", fps, "loop", loop, "bytes", 12 + arr.nbytes + anc.nbytes,
          "max|d|", int(np.abs(arr.astype(np.int32) - base_rs[None]).max()))

# ---------------------------------------------------------------- preview render
scene = bpy.context.scene
for o in list(scene.collection.all_objects):
    o.hide_render = True
cols = np.empty(len(faces) * 3 * 4, dtype=np.float32)
for fi, (r, g, b) in enumerate(face_rgb):
    for k in range(3):
        cols[(fi * 3 + k) * 4:(fi * 3 + k) * 4 + 4] = (r, g, b, 1.0)

weapons = {}
if WEAPON_DIR:
    from weapon_align import read_obj, align
    girl_height = float(-base_rs[:, 1].min()) / 128.0  # blender units
    units = girl_height / REFERENCE_HEIGHT  # blender units per RS unit
    for key, item, kind in [('bow', 861, 'bow'), ('staff', 1383, 'staff')]:
        path = os.path.join(WEAPON_DIR, f'{item}.obj')
        if os.path.exists(path):
            v, f = read_obj(path)
            hand, info = align(v, kind)
            weapons[key] = (hand * units, f)
            print("WEAPON", key, info)

CLIP_WEAPONS = {'idle': ['bow', 'staff'], 'walk': ['bow', 'staff'], 'attack_melee': [],
                'attack_ranged': ['bow'], 'attack_magic': ['staff']}
WEAPON_HAND = {'bow': 0, 'staff': 1}

spacing = 95.0
scene.render.engine = 'BLENDER_WORKBENCH'
scene.display.shading.light = 'FLAT'
scene.display.shading.color_type = 'VERTEX'
scene.world = scene.world or bpy.data.worlds.new('w')
scene.world.color = (0.2, 0.2, 0.25)
height = float(-base_rs[:, 1].min()) / 128.0
cam = bpy.data.cameras.new('pc'); cam.type = 'ORTHO'


def preview_object(name, world, tris, rgba):
    pm = bpy.data.meshes.new(name)
    pm.from_pydata(world.tolist(), [], tris)
    pm.update()
    ca = pm.color_attributes.new('Col', 'BYTE_COLOR', 'CORNER')
    if rgba is not None:
        c = np.empty(len(pm.loops) * 4, dtype=np.float32)
        c[0::4], c[1::4], c[2::4], c[3::4] = rgba
        ca.data.foreach_set('color', c)
    po = bpy.data.objects.new(name, pm)
    scene.collection.objects.link(po)
    return po


for name, _, _, _, _ in CLIPS:
    objs = []
    for col_i, (rs, hands) in enumerate(preview[name]):
        world = np.empty_like(rs, dtype=np.float32)
        world[:, 0] = rs[:, 0] / 128.0 + col_i * spacing
        world[:, 1] = rs[:, 2] / 128.0
        world[:, 2] = -rs[:, 1] / 128.0
        po = preview_object(f'pv_{name}_{col_i}', world, faces, None)
        po.data.color_attributes['Col'].data.foreach_set('color', cols)
        objs.append(po)
        for wkey in CLIP_WEAPONS[name]:
            if wkey not in weapons:
                continue
            hand_v, wf = weapons[wkey]
            origin, x, y, z = hands[WEAPON_HAND[wkey]]
            R = np.array([[x.x, y.x, z.x], [x.y, y.y, z.y], [x.z, y.z, z.z]], dtype=np.float64)
            wv = hand_v @ R.T + np.array([origin.x + col_i * spacing, origin.y, origin.z])
            objs.append(preview_object(f'pw_{name}_{col_i}_{wkey}', wv.astype(np.float32), wf.tolist(), (0.9, 0.5, 0.1, 1.0)))
    ncol = len(preview[name])
    width = ncol * spacing
    cam.ortho_scale = max(width * 1.05, height * 1.15)
    scene.render.resolution_x = 1600
    scene.render.resolution_y = int(1600 * (height * 1.15) / cam.ortho_scale)
    cx = width / 2 - spacing / 2; cz = height / 2
    for vname, pos, rot in [('front', (cx, -600, cz), (math.pi / 2, 0, 0)),
                            ('side', None, (math.pi / 2, 0, math.pi / 2))]:
        if vname == 'side':
            for po in objs:
                i = int(po.name.split('_')[-1 if po.name.startswith('pv') else -2])
                po.location = (-i * spacing, -i * spacing + (ncol - 1) * spacing / 2, 0)
            pos = (600, 0, cz)
        co = bpy.data.objects.new(f'cam_{name}_{vname}', cam)
        scene.collection.objects.link(co)
        co.location = pos; co.rotation_euler = rot
        scene.camera = co
        scene.render.filepath = os.path.join(OUT_DIR, f'preview_{name}_{vname}.png')
        bpy.ops.render.render(write_still=True)
    for po in objs:
        po.hide_render = True
print("DONE")
