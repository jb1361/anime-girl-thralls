# Turn the Rory Mercury statue (an unrigged, untextured STL scan) into a rigged, coloured, low poly
# character in the same A-pose and with the same bone names as the Esdeath rig, so export_girl.py can
# pose and bake her clips.
#
#   blender -b rory.blend --python rig_rory.py -- <out .blend> <target faces> [preview dir]
#
# Steps:
# 1. Split the statue into its print pieces (loose parts), drop the rock base, voxel remesh each piece into
#    a clean closed surface and decimate it to its share of the face budget. Colour each piece flat, with
#    a few regions picked by position (bare thighs, gloves).
# 2. Place an armature at the statue's joints (measured by hand from gridded renders) and weight each
#    piece to the bones that may move it.
# 3. Repose from the statue pose into Esdeath's A-pose bone directions (keeping Rory's bone lengths) by
#    linear blend skinning, scale her to Esdeath's height and save the A-pose as the rest pose.
#
# Her halberd is weighted to the right wrist. The right forearm is turned so that, in her base pose from
# girl_poses.py, the halberd stands upright with its butt on the floor.
import bpy, sys, os, math
import numpy as np
from mathutils import Matrix, Vector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from girl_poses import base_pose

argv = sys.argv[sys.argv.index('--') + 1:]
OUT_BLEND = argv[0]
TARGET_FACES = int(argv[1])
PREVIEW_DIR = argv[2] if len(argv) > 2 else None
VOXEL = 0.02  # statue units; the statue is about 15.5 units tall without its base
MIN_FACES = 40
TARGET_HEIGHT = 150.85  # Esdeath's height (feet to top of head) in blender units

SKIN = (0.93, 0.80, 0.72)
HAIR = (0.06, 0.05, 0.08)
DRESS = (0.09, 0.08, 0.10)
RED = (0.45, 0.04, 0.08)
STEEL = (0.32, 0.32, 0.36)
WOOD = (0.14, 0.10, 0.10)

# Loose part name -> (expected vertex count, role, colour, budget weight). The vertex counts guard
# against a different source file. The role picks the bones a piece is weighted to.
PARTS = {
    'Rory_Mercury.004': (999906, 'halberd', STEEL, 0.6),
    'Rory_Mercury.006': (150000, 'halberd', WOOD, 0.8),
    'Rory_Mercury.019': (149982, 'hair', HAIR, 1.0),
    'Rory_Mercury.001': (149953, 'skirt', DRESS, 1.0),
    'Rory_Mercury.003': (99525, 'tie', RED, 1.0),
    'Rory_Mercury.002': (74999, 'bodice', DRESS, 1.2),
    'Rory_Mercury.017': (35000, 'base', None, 0.0),
    'Rory_Mercury.016': (34999, 'head hair', HAIR, 1.5),
    'Rory_Mercury.009': (34998, 'head', SKIN, 3.0),
    'Rory_Mercury.021': (23564, 'right arm', SKIN, 2.0),
    'Rory_Mercury.007': (20000, 'right leg', DRESS, 1.2),
    'Rory_Mercury.018': (20000, 'sash', DRESS, 1.0),
    'Rory_Mercury.008': (19999, 'left leg', DRESS, 1.2),
    'Rory_Mercury': (14678, 'left arm', SKIN, 2.0),
    'Rory_Mercury.010': (5000, 'head hair', RED, 1.5),
    'Rory_Mercury.013': (2981, 'hair', HAIR, 1.0),
    'Rory_Mercury.011': (2980, 'hair', HAIR, 1.0),
}
TORSO = 'Rory_Mercury.002'
SHAFT = 'Rory_Mercury.006'
HEAD_TOP = ['Rory_Mercury.016', 'Rory_Mercury.010']  # top of her head

# Colour overrides inside a piece, by face centre in statue coordinates (first match wins).
# The skirt piece also holds the thighs (bare above the stocking lace); the forearms wear gloves.
REGIONS = {
    'Rory_Mercury.001': [
        (lambda c: c[0] > -1.0 and c[2] < 6.9, DRESS),  # standing leg: stocking top
        (lambda c: c[0] > -1.0 and c[2] < 7.25, SKIN),  # standing leg: thigh below the hem
        (lambda c: c[0] < -2.6 and c[2] < 8.15, DRESS),  # raised leg: stocking top
        (lambda c: c[0] < -2.1 and c[2] < 8.15, SKIN),  # raised leg: thigh below the hem
    ],
    'Rory_Mercury': [(lambda c: c[0] < 2.45, DRESS)],
    'Rory_Mercury.021': [(lambda c: c[0] > -3.0, DRESS)],
}

# Joints in statue coordinates (x her left, y back, z up). She stands on her left leg with the right
# leg raised and bent, the right hand gripping the halberd at shoulder height, the left arm out and down.
# The thigh axes come from cross-sections of the skirt piece, which also holds both thighs.
J = {
    'hips': (-0.76, 0.0, 8.45), 'spine': (-0.6, 0.0, 9.2), 'chest': (-0.3, 0.05, 9.9),
    'neck': (0.1, 0.0, 10.75), 'head': (0.3, 0.0, 11.05), 'head_top': (0.55, 0.05, 12.2),
    'r_clav': (-0.25, 0.05, 10.65), 'r_shoulder': (-0.75, 0.12, 10.75), 'r_elbow': (-1.8, 0.3, 10.55),
    'r_wrist': (-3.15, 0.4, 10.6), 'r_hand': (-3.55, 0.4, 10.55),
    'l_clav': (0.25, 0.05, 10.55), 'l_shoulder': (0.5, 0.05, 10.3), 'l_elbow': (1.25, -0.1, 9.3),
    'l_wrist': (2.2, -0.25, 8.28), 'l_hand': (2.75, -0.33, 7.75),
    'l_hip': (-0.45, -0.1, 8.35), 'l_knee': (-0.6, -0.05, 5.5), 'l_ankle': (-0.69, 0.31, 2.75), 'l_toe': (-0.78, 0.25, 1.5),
    'r_hip': (-1.15, -0.25, 8.2), 'r_knee': (-3.55, -0.25, 7.1), 'r_ankle': (-1.25, 1.0, 6.25), 'r_toe': (-1.1, 1.4, 5.6),
}

# name, head joint, tail joint, parent
BONES = [
    ('Hips', 'hips', 'spine', None),
    ('Spine', 'spine', 'chest', 'Hips'),
    ('Chest', 'chest', 'neck', 'Spine'),
    ('Neck', 'neck', 'head', 'Chest'),
    ('Head', 'head', 'head_top', 'Neck'),
    ('Left shoulder', 'l_clav', 'l_shoulder', 'Chest'),
    ('Left arm', 'l_shoulder', 'l_elbow', 'Left shoulder'),
    ('Left elbow', 'l_elbow', 'l_wrist', 'Left arm'),
    ('Left wrist', 'l_wrist', 'l_hand', 'Left elbow'),
    ('Right shoulder', 'r_clav', 'r_shoulder', 'Chest'),
    ('Right arm', 'r_shoulder', 'r_elbow', 'Right shoulder'),
    ('Right elbow', 'r_elbow', 'r_wrist', 'Right arm'),
    ('Right wrist', 'r_wrist', 'r_hand', 'Right elbow'),
    ('Left leg', 'l_hip', 'l_knee', 'Hips'),
    ('Left knee', 'l_knee', 'l_ankle', 'Left leg'),
    ('Left ankle', 'l_ankle', 'l_toe', 'Left knee'),
    ('Right leg', 'r_hip', 'r_knee', 'Hips'),
    ('Right knee', 'r_knee', 'r_ankle', 'Right leg'),
    ('Right ankle', 'r_ankle', 'r_toe', 'Right knee'),
]
BONE_INDEX = {b[0]: i for i, b in enumerate(BONES)}

# A-pose bone directions from the Esdeath rig (her left side; the right side mirrors x)
ESDEATH_DIRS = {
    'Hips': (0, 0, 1), 'Spine': (0, 1.35, 9.40), 'Chest': (0, 2.09, 14.56), 'Neck': (0, 0, 1),
    'Head': (0, -1.0, 9.7),
    'Left shoulder': (6.70, 0.42, -1.26), 'Left arm': (14.48, 0.56, -9.87),
    'Left elbow': (17.94, -0.08, -13.15), 'Left wrist': (6.71, 0.34, -4.47),
    'Left leg': (-0.98, 0.15, -34.68), 'Left knee': (1.60, 2.21, -40.07), 'Left ankle': (0, -0.3, -1),
}
# Esdeath's left finger bones relative to her left wrist head: name -> (head offset, tail offset, parent)
ESDEATH_WRIST = np.array((43.346, 3.009, 101.572))
ESDEATH_FOREARM = 22.24
ESDEATH_FINGERS = [
    ('Thumb1', (46.153, 0.383, 97.976), (47.922, -0.898, 95.403), None),
    ('Thumb2', (47.922, -0.898, 95.403), (50.143, -2.197, 93.22), 'Thumb1'),
    ('IndexFinger1', (49.994, 1.343, 96.895), (52.165, 1.359, 95.375), None),
    ('IndexFinger2', (52.165, 1.359, 95.375), (53.57, 1.315, 94.391), 'IndexFinger1'),
    ('IndexFinger3', (53.57, 1.315, 94.391), (54.975, 1.329, 93.407), 'IndexFinger2'),
    ('MiddleFinger1', (49.828, 2.929, 97.011), (52.545, 2.909, 95.109), None),
    ('MiddleFinger2', (52.545, 2.909, 95.109), (54.097, 2.862, 94.022), 'MiddleFinger1'),
    ('MiddleFinger3', (54.097, 2.862, 94.022), (55.649, 2.854, 92.935), 'MiddleFinger2'),
    ('RingFinger1', (49.825, 4.526, 97.013), (52.026, 4.501, 95.472), None),
    ('RingFinger2', (52.026, 4.501, 95.472), (53.604, 4.559, 94.367), 'RingFinger1'),
    ('RingFinger3', (53.604, 4.559, 94.367), (55.183, 4.529, 93.261), 'RingFinger2'),
    ('LittleFinger1', (49.25, 6.023, 97.416), (51.123, 5.965, 96.104), None),
    ('LittleFinger2', (51.123, 5.965, 96.104), (52.342, 5.885, 95.251), 'LittleFinger1'),
    ('LittleFinger3', (52.342, 5.885, 95.251), (53.563, 5.858, 94.396), 'LittleFinger2'),
]


def unit(v):
    v = np.asarray(v, dtype=np.float64)
    return v / np.linalg.norm(v)


def target_dir(name):
    if name.startswith('Right '):
        d = np.array(ESDEATH_DIRS['Left ' + name[6:]], dtype=np.float64)
        d[0] = -d[0]
        return unit(d)
    return unit(ESDEATH_DIRS[name])


def swing(a, b):
    """Smallest rotation taking unit vector a onto unit vector b."""
    a, b = unit(a), unit(b)
    v = np.cross(a, b)
    c = float(np.dot(a, b))
    if np.linalg.norm(v) < 1e-9:
        if c > 0:
            return np.eye(3)
        perp = unit(np.cross(a, (1, 0, 0) if abs(a[0]) < 0.9 else (0, 1, 0)))
        return np.array(Matrix.Rotation(math.pi, 3, Vector(perp)))
    vx = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + vx + vx @ vx * (1 / (1 + c))


def frame(a, b):
    a = unit(a)
    b = unit(b - np.dot(b, a) * a)
    return np.column_stack([a, b, np.cross(a, b)])


def rot_deg(x=0.0, y=0.0, z=0.0):
    """Same rotation as export_girl.set_bone: about armature axes, Y first, then X, then Z."""
    return np.array(Matrix.Rotation(math.radians(z), 3, 'Z') @ Matrix.Rotation(math.radians(x), 3, 'X')
                    @ Matrix.Rotation(math.radians(y), 3, 'Y'))


# ---------------------------------------------------------------- 1. pieces
src = bpy.data.objects['Rory_Mercury']
bpy.context.view_layer.objects.active = src
src.select_set(True)
bpy.ops.object.mode_set(mode='EDIT')
bpy.ops.mesh.select_all(action='SELECT')
bpy.ops.mesh.separate(type='LOOSE')
bpy.ops.object.mode_set(mode='OBJECT')

pieces = {}
for o in [o for o in bpy.data.objects if o.type == 'MESH']:
    n = len(o.data.vertices)
    if o.name not in PARTS:
        assert n < 100, ("unexpected part", o.name, n)
        continue
    assert n == PARTS[o.name][0], ("part changed", o.name, n, PARTS[o.name][0])
    if PARTS[o.name][2] is not None:
        pieces[o.name] = o
assert len(pieces) == sum(1 for p in PARTS.values() if p[2] is not None), sorted(pieces)


def area(o):
    a = np.empty(len(o.data.polygons), dtype=np.float32)
    o.data.polygons.foreach_get('area', a)
    return float(a.sum())


weighted_area = {name: area(o) * PARTS[name][3] for name, o in pieces.items()}
total_area = sum(weighted_area.values())
budget = {name: max(MIN_FACES, int(TARGET_FACES * a / total_area)) for name, a in weighted_area.items()}
squeeze = (TARGET_FACES - MIN_FACES * len(budget)) / max(1, sum(budget.values()) - MIN_FACES * len(budget))
budget = {name: MIN_FACES + int((b - MIN_FACES) * squeeze) for name, b in budget.items()}

colours = []  # distinct face colours, one material each
verts, faces, face_colour, vert_piece = [], [], [], []
in_region = set()  # vertices of faces coloured by a REGIONS rule
for name, o in sorted(pieces.items()):
    if len(o.data.polygons) > budget[name] * 4:
        rm = o.modifiers.new('rs_remesh', 'REMESH')
        rm.mode = 'VOXEL'
        rm.voxel_size = VOXEL
    dg = bpy.context.evaluated_depsgraph_get()
    remeshed = len(o.evaluated_get(dg).data.polygons)
    dec = o.modifiers.new('rs_decimate', 'DECIMATE')
    dec.use_collapse_triangulate = True
    dec.ratio = min(1.0, budget[name] / max(1, remeshed) / 2)  # quads count twice once triangulated
    dg = bpy.context.evaluated_depsgraph_get()
    eo = o.evaluated_get(dg)
    me = eo.to_mesh()
    me.calc_loop_triangles()
    co = np.array([eo.matrix_world @ v.co for v in me.vertices], dtype=np.float64)
    base = len(verts)
    rules = REGIONS.get(name, [])
    for tri in me.loop_triangles:
        a, b, c = tri.vertices
        if a == b or b == c or a == c:
            continue
        centre = (co[a] + co[b] + co[c]) / 3
        rgb = next((col for test, col in rules if test(centre)), None)
        if rgb is None:
            rgb = PARTS[name][2]
        else:
            in_region.update((base + a, base + b, base + c))
        if rgb not in colours:
            colours.append(rgb)
        faces.append((base + a, base + b, base + c))
        face_colour.append(colours.index(rgb))
    eo.to_mesh_clear()
    verts.extend(co.tolist())
    vert_piece.extend([name] * len(co))
    print("PIECE", PARTS[name][1], name, "remeshed", remeshed, "budget", budget[name], "verts", len(co))

V = np.array(verts, dtype=np.float64)
vert_piece = np.array(vert_piece)
role = np.array([PARTS[p][1] for p in vert_piece])

# ---------------------------------------------------------------- 2. weights (statue coordinates)
heads_s = np.array([J[b[1]] for b in BONES], dtype=np.float64)
tails_s = np.array([J[b[2]] for b in BONES], dtype=np.float64)


def seg_dist(p, h, t):
    d = t - h
    u = np.clip(((p - h) @ d) / float(d @ d), 0.0, 1.0)
    return np.linalg.norm(p - (h + u[:, None] * d), axis=1), u


def nearest_weights(p, names, power=4.0):
    d = np.stack([seg_dist(p, heads_s[BONE_INDEX[n]], tails_s[BONE_INDEX[n]])[0] for n in names], axis=1)
    w = (1.0 / (d + 0.05)) ** power
    w /= w.sum(axis=1, keepdims=True)
    w[w < 0.05] = 0.0
    return w / w.sum(axis=1, keepdims=True)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


W = np.zeros((len(V), len(BONES)))


def assign(mask, names, w):
    for k, n in enumerate(names):
        W[mask, BONE_INDEX[n]] += w[:, k]


def nearest(mask, names, power=4.0):
    assign(mask, names, nearest_weights(V[mask], names, power))


W[role == 'halberd', BONE_INDEX['Right wrist']] = 1.0
W[role == 'tie', BONE_INDEX['Chest']] = 1.0
W[role == 'sash', BONE_INDEX['Hips']] = 1.0
W[role == 'head hair', BONE_INDEX['Head']] = 1.0
# long hair: head above the neck, chest further down
m = role == 'hair'
up = smoothstep(J['neck'][2] - 1.2, J['neck'][2], V[m, 2])
assign(m, ['Head', 'Chest'], np.column_stack([up, 1 - up]))
nearest(role == 'head', ['Head', 'Neck', 'Chest'])
nearest(role == 'bodice', ['Hips', 'Spine', 'Chest', 'Left shoulder', 'Right shoulder', 'Left arm', 'Right arm'])
# limbs: sharp joints, since the statue's bent knee and elbows straighten a long way
nearest(role == 'left arm', ['Left arm', 'Left elbow', 'Left wrist'], 12.0)
nearest(role == 'right arm', ['Right arm', 'Right elbow', 'Right wrist'], 12.0)
nearest(role == 'left leg', ['Left leg', 'Left knee', 'Left ankle'], 12.0)
nearest(role == 'right leg', ['Right leg', 'Right knee', 'Right ankle'], 12.0)
# skirt piece: the fabric follows the hips, the thighs inside it (the bare skin and stocking tops
# picked out by REGIONS) follow the legs
m = np.where(role == 'skirt')[0]
thigh = np.array([i in in_region for i in m])
hips_w = np.ones(len(m))
for leg in ('Left leg', 'Right leg'):
    i = BONE_INDEX[leg]
    d, u = seg_dist(V[m], heads_s[i], tails_s[i])
    w = (1 - smoothstep(0.8, 1.0, d)) * smoothstep(0.05, 0.2, u) * thigh  # thighs and frills, about 0.4 thick
    w = np.minimum(w, hips_w)
    W[m, i] += w
    hips_w -= w
W[m, BONE_INDEX['Hips']] += hips_w
assert np.allclose(W.sum(axis=1), 1.0), "unweighted vertices"
for leg in ('Left leg', 'Right leg'):
    w = W[m, BONE_INDEX[leg]]
    print("THIGH", leg, "full", int((w > 0.99).sum()), "partial", int(((w > 0.01) & (w <= 0.99)).sum()))

# ---------------------------------------------------------------- 3. A-pose
# Halberd axis (butt -> head) and blade direction, from the shaft piece and the whole halberd
shaft = V[vert_piece == SHAFT]
centre = shaft.mean(axis=0)
axis = unit(np.linalg.svd(shaft - centre)[2][0])
halberd = V[role == 'halberd']
if (halberd.mean(axis=0) - centre) @ axis < 0:
    axis = -axis
blade = halberd.mean(axis=0) - centre
blade = unit(blade - (blade @ axis) * axis)

# The base pose's rotation of the right forearm (export_girl applies these about armature axes)
bp = base_pose('rory')
D = rot_deg(**{k: v for k, v in bp['Right arm'].items() if k != 'loc'}) @ rot_deg(**bp['Right elbow'])

rest_heads, rest_rot = {}, {}
for name, hj, tj, parent in BONES:
    i = BONE_INDEX[name]
    d_s = tails_s[i] - heads_s[i]
    if name == 'Right wrist':
        # halberd upright in the base pose, blade toward the heading that bends the wrist the least
        f_a = target_dir('Right elbow')
        best = None
        for deg in range(0, 360, 5):
            th = math.radians(deg)
            T = frame((0, 0, 1), (math.sin(th), -math.cos(th), 0)) @ frame(axis, blade).T
            R = D.T @ T
            bend = math.degrees(math.acos(np.clip(unit(R @ (J['r_wrist'] - np.array(J['r_elbow']))) @ f_a, -1, 1)))
            if best is None or bend < best[0]:
                best = (bend, deg, R)
        print("HALBERD wrist bend", round(best[0], 1), "blade heading", best[1])
        R = best[2]
        # the forearm points exactly along the A-pose direction with the wrist's twist
        f_s = unit(np.array(J['r_wrist']) - np.array(J['r_elbow']))
        rest_rot['Right elbow'] = swing(R @ f_s, target_dir('Right elbow')) @ R
    elif name == 'Right elbow':
        R = None  # set with the wrist
    else:
        R = swing(d_s, target_dir(name))
    if R is not None:
        rest_rot[name] = R
order = [b[0] for b in BONES]
for name, hj, tj, parent in BONES:
    i = BONE_INDEX[name]
    if parent is None:
        rest_heads[name] = heads_s[i].copy()
    else:
        p = BONE_INDEX[parent]
        rest_heads[name] = rest_heads[parent] + rest_rot[parent] @ (heads_s[i] - heads_s[p])
rest_tails = {b[0]: rest_heads[b[0]] + rest_rot[b[0]] @ (tails_s[BONE_INDEX[b[0]]] - heads_s[BONE_INDEX[b[0]]])
              for b in BONES}

Va = np.zeros_like(V)
for name, i in BONE_INDEX.items():
    w = W[:, i]
    if not w.any():
        continue
    Va += w[:, None] * ((V - heads_s[i]) @ rest_rot[name].T + rest_heads[name])

# Scale to Esdeath's height and stand her on the origin
body = role != 'halberd'
top = max(float(Va[vert_piece == p][:, 2].max()) for p in HEAD_TOP)
floor = float(Va[body][:, 2].min())
s = TARGET_HEIGHT / (top - floor)
offset = np.array([rest_heads['Hips'][0], rest_heads['Hips'][1], floor])
Va = (Va - offset) * s
for d in (rest_heads, rest_tails):
    for k in d:
        d[k] = (d[k] - offset) * s

# Slide the halberd along its shaft so its butt touches the floor in the base pose
a_rest = rest_rot['Right wrist'] @ axis
wr = rest_heads['Right wrist']
er = rest_heads['Right elbow']
ar = rest_heads['Right arm']
Rw_arm = rot_deg(**{k: v for k, v in bp['Right arm'].items() if k != 'loc'})
wrist_base = ar + Rw_arm @ (er - ar) + D @ (wr - er)
hal = role == 'halberd'
butt_base = min(float((wrist_base + D @ (p - wr))[2]) for p in Va[hal])
Va[hal] -= a_rest * (butt_base - 0.5)
print("HALBERD slide", round(butt_base - 0.5, 1), "top in base pose",
      round(max(float((wrist_base + D @ (p - wr))[2]) for p in Va[hal]), 1))

# ---------------------------------------------------------------- 4. Blender objects
for o in list(bpy.data.objects):
    bpy.data.objects.remove(o)
me = bpy.data.meshes.new('Body')
me.from_pydata(Va.tolist(), [], faces)
me.update()
for k, rgb in enumerate(colours):
    mat = bpy.data.materials.new(f'rory_{k}')
    mat.diffuse_color = (*rgb, 1.0)
    me.materials.append(mat)
me.polygons.foreach_set('material_index', face_colour)
me.update()
body_obj = bpy.data.objects.new('Body', me)
bpy.context.scene.collection.objects.link(body_obj)

arm_data = bpy.data.armatures.new('Armature')
arm_obj = bpy.data.objects.new('Armature', arm_data)
bpy.context.scene.collection.objects.link(arm_obj)
bpy.context.view_layer.objects.active = arm_obj
bpy.ops.object.mode_set(mode='EDIT')
eb = {}
for name, hj, tj, parent in BONES:
    b = arm_data.edit_bones.new(name)
    b.head = Vector(rest_heads[name])
    b.tail = Vector(rest_tails[name])
    if parent:
        b.parent = eb[parent]
    eb[name] = b
for side, sign in (('L', 1.0), ('R', -1.0)):
    wrist = eb['Left wrist' if side == 'L' else 'Right wrist']
    forearm = rest_tails['Left elbow' if side == 'L' else 'Right elbow'] - rest_heads['Left elbow' if side == 'L' else 'Right elbow']
    k = np.linalg.norm(forearm) / ESDEATH_FOREARM
    origin = np.array(wrist.head)
    offsets = {}
    for fname, h, t, parent in ESDEATH_FINGERS:
        hv = (np.array(h) - ESDEATH_WRIST) * k
        tv = (np.array(t) - ESDEATH_WRIST) * k
        hv[0] *= sign
        tv[0] *= sign
        offsets[fname] = (hv, tv)
    if side == 'R':
        # Turn the fist so its weapon axis (little -> index finger) runs up the halberd shaft and the
        # knuckles face away from the wrist, like the gripping hand of the mesh
        fist = frame(offsets['IndexFinger1'][0] - offsets['LittleFinger1'][0], offsets['MiddleFinger1'][0])
        grip = frame(a_rest, rest_tails['Right wrist'] - rest_heads['Right wrist'])
        Q = grip @ fist.T
        offsets = {n: (Q @ hv, Q @ tv) for n, (hv, tv) in offsets.items()}
    for fname, h, t, parent in ESDEATH_FINGERS:
        hv, tv = offsets[fname]
        b = arm_data.edit_bones.new(f'{fname}_{side}')
        b.head = Vector(origin + hv)
        b.tail = Vector(origin + tv)
        b.parent = eb[f'{parent}_{side}'] if parent else wrist
        eb[f'{fname}_{side}'] = b
bpy.ops.object.mode_set(mode='OBJECT')

for name, i in BONE_INDEX.items():
    idx = np.nonzero(W[:, i] > 0)[0]
    if len(idx) == 0:
        continue
    vg = body_obj.vertex_groups.new(name=name)
    for vi in idx:
        vg.add([int(vi)], float(W[vi, i]), 'REPLACE')
mod = body_obj.modifiers.new('Armature', 'ARMATURE')
mod.object = arm_obj
body_obj.parent = arm_obj
print("STATS verts", len(Va), "faces", len(faces), "colours", len(colours), "scale", round(s, 3))
bpy.ops.wm.save_as_mainfile(filepath=OUT_BLEND)

# ---------------------------------------------------------------- previews: rest pose and base pose
if PREVIEW_DIR:
    scene = bpy.context.scene
    scene.render.engine = 'BLENDER_WORKBENCH'
    scene.display.shading.light = 'STUDIO'
    scene.display.shading.color_type = 'MATERIAL'
    scene.render.resolution_x = 800
    scene.render.resolution_y = 1000
    cam = bpy.data.cameras.new('pc')
    cam.type = 'ORTHO'
    cam.ortho_scale = 260
    co = bpy.data.objects.new('pcam', cam)
    scene.collection.objects.link(co)
    scene.camera = co
    rest = {b.name: b.matrix_local.to_3x3() for b in arm_data.bones}

    def pose(p):
        for pb in arm_obj.pose.bones:
            pb.rotation_mode = 'QUATERNION'
            pb.rotation_quaternion = (1, 0, 0, 0)
        for n, v in p.items():
            if n.startswith('_'):
                continue
            R = rest[n]
            Rw = Matrix(rot_deg(v.get('x', 0.0), v.get('y', 0.0), v.get('z', 0.0)).tolist())
            arm_obj.pose.bones[n].rotation_quaternion = (R.inverted() @ Rw @ R).to_quaternion()
        bpy.context.view_layer.update()

    for pname, p in (('rest', {}), ('base', bp)):
        pose(p)
        for vname, loc, rot in (('front', (0, -500, 100), (math.pi / 2, 0, 0)),
                                ('side', (500, 0, 100), (math.pi / 2, 0, math.pi / 2))):
            co.location = loc
            co.rotation_euler = rot
            scene.render.filepath = os.path.join(PREVIEW_DIR, f'rig_{pname}_{vname}.png')
            bpy.ops.render.render(write_still=True)
print("DONE")
