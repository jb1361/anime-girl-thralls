"""Blender CLI: convert a registered glTF model into a development mesh and review renders.

blender -b --factory-startup --disable-autoexec --python tools/convert_v2_blender.py -- aqua
All outputs stay under ignored assets/v2. Never runs scripts from downloaded files.
"""
import bpy
import bmesh
import colorsys
import hashlib
import json
import math
import re
import statistics
import sys
import zipfile
from pathlib import Path
from mathutils import Matrix, Vector

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from mesh_encoding import encode_mesh

def unpack(folder, record):
    archive = folder / record['file']
    if hashlib.sha256(archive.read_bytes()).hexdigest() != record['sha256']:
        raise ValueError('Source checksum changed')
    source = folder / 'source'
    source.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        if sum(i.file_size for i in z.infolist()) > 512 * 1024**2:
            raise ValueError('Archive expands beyond 512 MiB')
        for entry in z.infolist():
            path = source / entry.filename
            if not path.resolve().is_relative_to(source.resolve()) or '\\' in entry.filename:
                raise ValueError('Unsafe archive path')
        z.extractall(source)
    files = list(source.rglob('*.gltf'))
    if len(files) != 1:
        raise ValueError('Expected one glTF scene; select the intended scene manually')
    return files[0]

def relaxed_pose(reset=True):
    changed = []
    for obj in list(bpy.context.scene.objects):
        if obj.type != 'ARMATURE':
            continue
        obj.animation_data_clear()
        if reset:
            for bone in obj.pose.bones:
                bone.matrix_basis.identity()
        bpy.context.view_layer.update()
        for bone in obj.pose.bones:
            name = re.sub('[^a-z]', '', bone.name.lower())
            upper = ('upperarm' in name or name.endswith('leftarm') or name.endswith('rightarm')
                     or name in ('arml', 'armr', 'larm', 'rarm'))
            if not upper or any(word in name for word in ('twist', 'forearm', 'lowerarm')):
                continue
            current = bone.tail - bone.head
            world = obj.matrix_world.to_3x3() @ current
            if world.length == 0 or abs(world.x) < world.length * 0.45:
                continue
            desired_world = Vector((math.copysign(0.2, world.x), 0, -0.98))
            desired = obj.matrix_world.inverted().to_3x3() @ desired_world
            rotation = current.rotation_difference(desired).to_matrix().to_4x4()
            bone.matrix = Matrix.Translation(bone.head) @ rotation @ Matrix.Translation(-bone.head) @ bone.matrix
            bpy.context.view_layer.update()
            changed.append(bone.name)
    return changed

def sample_socket(socket, uv, vertex_color, cache):
    if not socket.is_linked:
        return list(socket.default_value)
    node = socket.links[0].from_node
    if node.type == 'TEX_IMAGE' and node.image:
        image = node.image
        if image.name not in cache:
            cache[image.name] = (list(image.pixels), tuple(image.size))
        pixels, (width, height) = cache[image.name]
        if not width or not height:
            raise ValueError('Missing image pixels: ' + image.name)
        x = min(width-1, max(0, int((uv.x % 1) * width)))
        y = min(height-1, max(0, int((uv.y % 1) * height)))
        return pixels[(y*width+x)*4:(y*width+x)*4+4]
    if node.type in ('VERTEX_COLOR', 'ATTRIBUTE'):
        return list(vertex_color)
    if node.type == 'MIX' and node.data_type == 'RGBA' and node.blend_type == 'MULTIPLY':
        if node.inputs[0].is_linked:
            raise ValueError('Linked color blend factor is not supported')
        factor = node.inputs[0].default_value
        a = sample_socket(node.inputs[6], uv, vertex_color, cache)
        b = sample_socket(node.inputs[7], uv, vertex_color, cache)
        return [a[i] * (1-factor+factor*b[i]) for i in range(3)] + [a[3]*b[3]]
    raise ValueError('Unsupported linked color shader: ' + node.type)

def material_sample(material, uv, vertex_color, cache):
    if material is None:
        return (0.7, 0.7, 0.7, 1)
    rgba = list(material.diffuse_color)
    if material.use_nodes:
        principled = next((n for n in material.node_tree.nodes if n.type in ('BSDF_PRINCIPLED', 'EMISSION')), None)
        if principled:
            socket = principled.inputs['Base Color' if principled.type == 'BSDF_PRINCIPLED' else 'Color']
            if principled.type == 'BSDF_PRINCIPLED' and not socket.is_linked and max(socket.default_value[:3]) == 0 and principled.inputs['Emission Color'].is_linked:
                socket = principled.inputs['Emission Color']
            rgba = sample_socket(socket, uv, vertex_color, cache)
            if principled.type == 'BSDF_PRINCIPLED' and principled.inputs['Alpha'].default_value < 1:
                rgba[3] = principled.inputs['Alpha'].default_value
    return rgba

def mesh_data(objects, yaw):
    points, triangles, samples = [], [], []
    image_cache = {}
    rotation = Matrix.Rotation(math.radians(yaw), 4, 'Z')
    for obj in objects:
        mesh = obj.data
        mesh.calc_loop_triangles()
        offset = len(points)
        world = rotation @ obj.matrix_world
        points.extend(world @ vertex.co for vertex in mesh.vertices)
        uv_layer = mesh.uv_layers.active
        color_layer = mesh.color_attributes.active_color
        for tri in mesh.loop_triangles:
            coords = [points[offset + i] for i in tri.vertices]
            normal = (coords[1]-coords[0]).cross(coords[2]-coords[0])
            if normal.length == 0:
                continue
            shade = 0.8 + 0.2 * max(0, normal.normalized().dot(Vector((-0.35,-0.76,0.55)).normalized()))
            material = mesh.materials[tri.material_index] if tri.material_index < len(mesh.materials) else None
            uvs = [uv_layer.data[i].uv.copy() if uv_layer else Vector((0,0)) for i in tri.loops]
            center = sum(uvs, Vector((0,0))) / 3
            colors = []
            for loop, vertex, uv in zip(tri.loops, tri.vertices, uvs):
                rgba = (1,1,1,1)
                if color_layer:
                    idx = loop if color_layer.domain == 'CORNER' else vertex
                    rgba = color_layer.data[idx].color
                rgba = material_sample(material, uv * 0.9 + center * 0.1, rgba, image_cache)
                colors.append(tuple(max(0,min(255, round(c * shade * 255 / 4) * 4)) for c in rgba[:3]) + (round(rgba[3], 3),))
            if all(c[3] < 0.1 for c in colors):
                continue
            triangles.append([offset + i for i in tri.vertices])
            samples.append(colors)
    if not points or not triangles:
        raise ValueError('No visible triangles')
    used = sorted({index for face in triangles for index in face})
    compact = {old: new for new, old in enumerate(used)}
    points = [points[index] for index in used]
    triangles = [[compact[index] for index in face] for face in triangles]
    low = [min(v[i] for v in points) for i in range(3)]
    high = [max(v[i] for v in points) for i in range(3)]
    scale = 200 / (high[2] - low[2])
    cx, cy = (low[0]+high[0])/2, (low[1]+high[1])/2
    vertex_map, vertices, remap = {}, [], []
    for p in points:
        value = (round((p.x-cx)*scale), round(-(p.z-low[2])*scale), round((p.y-cy)*scale))
        if value not in vertex_map:
            vertex_map[value] = len(vertices)
            vertices.append(value)
        remap.append(vertex_map[value])
    faces, palette, color_map, indices = [], [], {}, []
    for tri, colors in zip(triangles, samples):
        face = [remap[i] for i in tri]
        p = [Vector(vertices[i]) for i in face]
        if len(set(face)) < 3 or (p[1]-p[0]).cross(p[2]-p[0]).length == 0:
            continue
        faces.append(face)
        for color in colors:
            if color not in color_map:
                h,l,s = colorsys.rgb_to_hls(*(v/255 for v in color[:3]))
                color_map[color] = len(palette)
                palette.append([round(1-h,6),round(l,6),round(s,6),color[3]])
            indices.append(color_map[color])
    used = sorted({v for f in faces for v in f})
    compact = {v:i for i,v in enumerate(used)}
    return dict(useVertexColours=True, vertices=[vertices[i] for i in used],
                faces=[[compact[v] for v in f] for f in faces], vertexColours=palette,
                vertexColourIndex=indices, faceColours=[], faceColourIndex=[], priorities=[0]*len(faces))

def prepare_unrigged_pose(objects, options):
    pose = options.get('unriggedArmPose')
    if not pose:
        return
    points = [o.matrix_world @ v.co for o in objects for v in o.data.vertices]
    floor = min(p.z for p in points)
    height = max(p.z for p in points) - floor
    center = (min(p.x for p in points) + max(p.x for p in points)) / 2
    if pose.get('centerMode') == 'median':
        center = statistics.median(p.x for p in points)
    for obj in objects:
        inverse = obj.matrix_world.inverted()
        for vertex in obj.data.vertices:
            p = obj.matrix_world @ vertex.co
            x, z = (p.x-center)/height, (p.z-floor)/height
            if not pose['heightBand'][0] <= z <= pose['heightBand'][1] or abs(x) <= pose['blendStart']:
                continue
            weight = min(1, (abs(x)-pose['blendStart'])/(pose['blendEnd']-pose['blendStart']))
            angle = math.radians(pose['degrees']) * weight
            dx, dz = abs(x)-pose['shoulder'][0], z-pose['shoulder'][1]
            p.x = center + math.copysign(pose['shoulder'][0] + dx*math.cos(angle) + dz*math.sin(angle),x)*height
            p.z = floor + (pose['shoulder'][1] - dx*math.sin(angle) + dz*math.cos(angle))*height
            vertex.co = inverse @ p

def render_export(mesh, path):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    data = bpy.data.meshes.new('Converted mesh')
    data.from_pydata([(x,z,-y) for x,y,z in mesh['vertices']], [], mesh['faces'])
    data.update()
    obj = bpy.data.objects.new('V2 preview', data)
    bpy.context.collection.objects.link(obj)
    colors = data.color_attributes.new(name='Color', type='FLOAT_COLOR', domain='CORNER')
    for i, index in enumerate(mesh['vertexColourIndex']):
        h,l,s,a = mesh['vertexColours'][index]
        colors.data[i].color_srgb = (*colorsys.hls_to_rgb(1-h,l,s), a)
    material = bpy.data.materials.new('Baked colors')
    material.use_nodes = True
    material.node_tree.nodes.clear()
    out = material.node_tree.nodes.new('ShaderNodeOutputMaterial')
    emission = material.node_tree.nodes.new('ShaderNodeEmission')
    attr = material.node_tree.nodes.new('ShaderNodeVertexColor')
    attr.layer_name = 'Color'
    material.node_tree.links.new(attr.outputs['Color'], emission.inputs['Color'])
    material.node_tree.links.new(emission.outputs[0], out.inputs['Surface'])
    data.materials.append(material)
    scene = bpy.context.scene
    scene.render.engine = 'CYCLES'
    scene.cycles.samples = 8
    scene.render.resolution_x = 400
    scene.render.resolution_y = 440
    scene.render.resolution_percentage = 100
    scene.view_settings.view_transform = 'Standard'
    scene.world = bpy.data.worlds.new('Review background')
    scene.world.color = (0.08,0.08,0.08)
    camera_data = bpy.data.cameras.new('Review camera')
    camera = bpy.data.objects.new('Review camera', camera_data)
    bpy.context.collection.objects.link(camera)
    camera_data.type = 'ORTHO'
    camera_data.ortho_scale = 255
    scene.camera = camera
    for name, angle in [('front',0),('side',90),('back',180)]:
        radians = math.radians(angle)
        camera.location = (400*math.sin(radians),-400*math.cos(radians),115)
        camera.rotation_euler = (Vector((0,0,100))-camera.location).to_track_quat('-Z','Y').to_euler()
        scene.render.filepath = str(path / (name+'.png'))
        bpy.ops.render.render(write_still=True)

def main():
    base = sys.argv[sys.argv.index('--')+1]
    if not re.fullmatch('[a-z][a-z0-9_]*', base):
        raise ValueError('Invalid base name')
    folder = ROOT / 'assets/models' / (base+'_v2')
    record = json.loads((folder/'source.json').read_text(encoding='utf-8'))
    if record['license']['slug'] != 'by':
        raise ValueError('CC BY required for this V2 set')
    options_path = folder/'conversion.json'
    options = json.loads(options_path.read_text()) if options_path.exists() else {}
    scene_file = unpack(folder,record)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(scene_file))
    for name, rgba in options.get('materialColors', {}).items():
        material = bpy.data.materials.get(name)
        if material is None:
            raise ValueError('Unknown material override: ' + name)
        material.diffuse_color = rgba
        for node in material.node_tree.nodes if material.use_nodes else []:
            if node.type == 'BSDF_PRINCIPLED':
                node.inputs['Base Color'].default_value = rgba
    actions = [a.name for a in bpy.data.actions]
    if options.get('preserveImportedPose'):
        bpy.context.scene.frame_set(options.get('poseFrame', 1))
        bpy.context.view_layer.update()
        arms = relaxed_pose(reset=False) if options.get('relaxImportedPose') else []
    else:
        arms = relaxed_pose()
    objects = []
    excluded = []
    widgets = {bone.custom_shape for rig in bpy.context.scene.objects if rig.type == 'ARMATURE'
               for bone in rig.pose.bones if bone.custom_shape}
    for obj in list(bpy.context.scene.objects):
        if obj.type != 'MESH':
            continue
        if obj in widgets or obj.hide_render or any(re.search(pattern,obj.name,re.I) for pattern in options.get('exclude',[])):
            excluded.append(obj.name)
            continue
        evaluated = obj.evaluated_get(bpy.context.evaluated_depsgraph_get())
        data = bpy.data.meshes.new_from_object(evaluated, preserve_all_data_layers=True,
                                             depsgraph=bpy.context.evaluated_depsgraph_get())
        clone = bpy.data.objects.new(obj.name+'_baked', data)
        bpy.context.collection.objects.link(clone)
        clone.matrix_world = obj.matrix_world.copy()
        cuts = options.get('subdivide', {}).get(obj.name, 0)
        if cuts:
            bm = bmesh.new()
            bm.from_mesh(data)
            bmesh.ops.subdivide_edges(bm, edges=list(bm.edges), cuts=cuts, use_grid_fill=True)
            bm.to_mesh(data)
            bm.free()
        objects.append(clone)
    prepare_unrigged_pose(objects, options)
    if options.get('floorCutFraction'):
        zs = [(obj.matrix_world @ v.co).z for obj in objects for v in obj.data.vertices]
        cutoff = min(zs) + (max(zs)-min(zs))*options['floorCutFraction']
        for obj in objects:
            bm = bmesh.new()
            bm.from_mesh(obj.data)
            remove = [face for face in bm.faces if all((obj.matrix_world @ v.co).z <= cutoff for v in face.verts)]
            bmesh.ops.delete(bm, geom=remove, context='FACES')
            bm.to_mesh(obj.data)
            bm.free()
    originals = sum(len(o.data.polygons) for o in objects)
    mesh = mesh_data(objects, options.get('yaw',0))
    passes = 0
    while len(mesh['faces']) > 6000 or len(mesh['vertices']) > 3800:
        if passes >= 4:
            raise ValueError('Could not fit geometry budget; manual retopology required')
        ratio = min(5800/len(mesh['faces']), 3600/len(mesh['vertices']), 0.9)
        for obj in objects:
            bpy.context.view_layer.objects.active = obj
            modifier = obj.modifiers.new('Budget reduction','DECIMATE')
            modifier.ratio = ratio
            modifier.use_collapse_triangulate = True
            bpy.ops.object.modifier_apply(modifier=modifier.name)
        mesh = mesh_data(objects, options.get('yaw',0))
        passes += 1
    out = ROOT/'assets/v2'/base
    out.mkdir(parents=True,exist_ok=True)
    (out/(base+'_v2.json')).write_text(json.dumps(encode_mesh(mesh),separators=(',',':'))+'\n')
    audit = dict(base=base, source=record, vertices=len(mesh['vertices']), triangles=len(mesh['faces']),
                 originalPolygons=originals, reductionPasses=passes, excluded=excluded, armsRelaxed=arms,
                 sourceAnimationClips=actions, conversionOptions=options,
                 pose='Rest pose with recognized upper arms lowered; explicit unrigged pose adjustment where configured',
                 motion='Static geometry; original animation clips not exported',
                 review='Pending visual inspection; not published')
    (out/'conversion-report.json').write_text(json.dumps(audit,indent=2)+'\n',encoding='utf-8')
    attribution = (f"{record['title']}\nAuthor: {record['author']['displayName']} ({record['author']['profileUrl']})\n"
                   f"Source: {record['sourceUrl']}\nLicense: {record['license']['url']}\n\n"
                   "Modified for Custom Anime Thralls: baked pose and transforms, normalized height and origin, "
                   "sampled materials into vertex colors, welded vertices, removed degenerate triangles, "
                   "and reduced geometry where required. " + options.get('notes', '') + " No endorsement implied.\n"
                   "This model remains under its source CC BY license, separate from the plugin's BSD code.\n")
    (out/'ATTRIBUTION.txt').write_text(attribution,encoding='utf-8')
    render_export(mesh,out)
    print('V2_RESULT='+json.dumps({k:audit[k] for k in ('base','vertices','triangles','reductionPasses','armsRelaxed','sourceAnimationClips')}))

if __name__ == '__main__':
    main()
