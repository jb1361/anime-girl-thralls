"""Export matched vertex-colour JSON and AGTC v2 clips from reviewed glTF rigs.

blender -b --factory-startup --disable-autoexec --python tools/export_rigged_v2.py -- frieren saitama
No scripts from downloaded files are executed. Outputs go to assets/animation-trial.
"""
import bpy
import bmesh
import argparse
import colorsys
import hashlib
import json
import math
import re
import struct
import sys
from pathlib import Path
from mathutils import Matrix, Quaternion, Vector
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from convert_v2_blender import material_sample, unpack
from mesh_encoding import encode_mesh
import retargeted_poses

parser = argparse.ArgumentParser()
parser.add_argument('characters', nargs='+')
parser.add_argument('--continue-on-error', action='store_true')
parser.add_argument('--source-root', type=Path, default=ROOT)
parser.add_argument('--output-root', type=Path, default=ROOT/'assets/animation-trial')
args = parser.parse_args(sys.argv[sys.argv.index('--')+1:])

PROFILES = json.loads(Path(__file__).with_name('animation_profiles.json').read_text())

def export(base):
    settings = PROFILES[base]
    profile = settings['bones']
    motion = settings['motion']
    if motion != 'esdeath':
        raise ValueError('Only reviewed Esdeath-adapted profiles are supported')
    clips = retargeted_poses.CLIPS
    folder = args.source_root/'assets/models'/(base+'_v2')
    source = json.loads((folder/'source.json').read_text())
    if source['license']['slug'] != 'by':
        raise ValueError('Expected reviewed CC BY source')
    options = json.loads((folder/'conversion.json').read_text()) if (folder/'conversion.json').exists() else {}
    options['exclude'] = options.get('exclude',[]) + settings.get('exclude',[])
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(unpack(folder,source)))
    rigs = [o for o in bpy.context.scene.objects if o.type == 'ARMATURE']
    if len(rigs) != 1: raise ValueError('Profile requires exactly one armature')
    rig = rigs[0]
    for name in profile.values():
        if name not in rig.pose.bones: raise ValueError('Missing mapped bone: '+name)
    if options.get('preserveImportedPose'):
        bpy.context.scene.frame_set(options.get('poseFrame',1))
        bpy.context.view_layer.update()
    else:
        for bone in rig.pose.bones: bone.matrix_basis.identity()
    for obj in bpy.context.scene.objects: obj.animation_data_clear()
    bpy.context.view_layer.update()
    for side in ('L','R'):
        bone=rig.pose.bones[profile['arm'+side]]
        # Some imported BVH bones have arbitrary display tails; use the elbow joint.
        current=rig.pose.bones[profile['elbow'+side]].head-bone.head
        world=rig.matrix_world.to_3x3() @ current
        if world.length and abs(world.x)>world.length*.45:
            desired=rig.matrix_world.inverted().to_3x3() @ Vector((math.copysign(.2,world.x),0,-.98))
            rotation=current.rotation_difference(desired).to_matrix().to_4x4()
            bone.matrix=Matrix.Translation(bone.head) @ rotation @ Matrix.Translation(-bone.head) @ bone.matrix
            bpy.context.view_layer.update()
    for name,rgba in options.get('materialColors',{}).items():
        material=bpy.data.materials.get(name)
        if material is None: raise ValueError('Missing material override: '+name)
        material.diffuse_color=rgba
        for node in material.node_tree.nodes if material.use_nodes else []:
            if node.type=='BSDF_PRINCIPLED': node.inputs['Base Color'].default_value=rgba
    resting = {b.name:b.matrix_basis.copy() for b in rig.pose.bones}
    widgets = {b.custom_shape for b in rig.pose.bones if b.custom_shape}
    objects = [o for o in bpy.context.scene.objects if o.type=='MESH' and o not in widgets and not o.hide_render
               and not any(re.search(p,o.name,re.I) for p in options.get('exclude',[]))]
    for obj in objects:
        # Subdivide the rest mesh, retaining skin weights and UVs through BMesh layers.
        cuts = options.get('subdivide',{}).get(obj.name,0)
        if cuts:
            bm=bmesh.new();bm.from_mesh(obj.data)
            bmesh.ops.subdivide_edges(bm,edges=list(bm.edges),cuts=cuts,use_grid_fill=True)
            bm.to_mesh(obj.data);bm.free()
    def evaluated():
        points=[]; triangles=[]; colors=[]; cache={}
        dg=bpy.context.evaluated_depsgraph_get()
        for obj in objects:
            eo=obj.evaluated_get(dg); mesh=eo.to_mesh();mesh.calc_loop_triangles()
            offset=len(points)
            points.extend(eo.matrix_world @ v.co for v in mesh.vertices)
            uv=mesh.uv_layers.active; layer=mesh.color_attributes.active_color
            for tri in mesh.loop_triangles:
                triangles.append([offset+i for i in tri.vertices])
                mat=mesh.materials[tri.material_index] if tri.material_index<len(mesh.materials) else None
                if not collect_colors: continue
                uvs=[uv.data[i].uv.copy() if uv else Vector((0,0)) for i in tri.loops]
                center=sum(uvs,Vector((0,0)))/3
                coords=[points[offset+i] for i in tri.vertices]
                normal=(coords[1]-coords[0]).cross(coords[2]-coords[0]).normalized()
                shade=.8+.2*max(0,normal.dot(Vector((-.35,-.76,.55)).normalized()))
                row=[]
                for loop,vertex,uvcoord in zip(tri.loops,tri.vertices,uvs):
                    vc=layer.data[loop if layer.domain=='CORNER' else vertex].color if layer else (1,1,1,1)
                    rgba=material_sample(mat,uvcoord*.9+center*.1,vc,cache)
                    row.append(tuple(max(0,min(255,round(c*shade*255/4)*4)) for c in rgba[:3])+(round(rgba[3],3),))
                colors.append(row)
            eo.to_mesh_clear()
        return np.array(points),triangles,colors
    collect_colors=True
    points,triangles,colors=evaluated()
    original_counts=(len(points),len(triangles))
    reduction_passes=0
    while len(points)>3600 or len(triangles)>5800:
        if reduction_passes>=5:
            raise ValueError(f'Skin-preserving reduction still has {len(points)} vertices/{len(triangles)} faces; needs mesh cleanup')
        ratio=min(3500/len(points),5600/len(triangles),.92)
        for obj in objects:
            modifier=obj.modifiers.get('Animation budget')
            if modifier is None:
                modifier=obj.modifiers.new('Animation budget','DECIMATE')
                modifier.ratio=1
                modifier.use_collapse_triangulate=True
                obj.modifiers.move(len(obj.modifiers)-1,0)
            modifier.ratio*=ratio
        bpy.context.view_layer.update()
        points,triangles,colors=evaluated()
        reduction_passes+=1
    low=points.min(axis=0);high=points.max(axis=0);height=high[2]-low[2]
    origin=np.array([(low[0]+high[0])/2,(low[1]+high[1])/2,low[2]])
    scale=200/height
    def coords(p):
        v=(p-origin)*scale
        return np.rint(np.stack((v[...,0],-v[...,2],v[...,1]),axis=-1)).astype(np.int32)
    base_vertices=coords(points)
    kept=[];palette=[];color_map={};indices=[]
    for index,(face,row) in enumerate(zip(triangles,colors)):
        p=base_vertices[face]
        if np.all(np.cross(p[1]-p[0],p[2]-p[0])==0) or all(c[3]<.1 for c in row): continue
        kept.append(index)
        for c in row:
            if c not in color_map:
                h,l,s=colorsys.rgb_to_hls(*(v/255 for v in c[:3]))
                color_map[c]=len(palette);palette.append([round(1-h,6),round(l,6),round(s,6),c[3]])
            indices.append(color_map[c])
    used=sorted({i for f in kept for i in triangles[f]});remap={v:i for i,v in enumerate(used)}
    faces=[[remap[i] for i in triangles[f]] for f in kept]
    model=dict(useVertexColours=True,vertices=base_vertices[used].tolist(),faces=faces,vertexColours=palette,
               vertexColourIndex=indices,faceColours=[],faceColourIndex=[],priorities=[0]*len(faces))
    def rotate(key,x=0,y=0,z=0,loc=None):
        bone=rig.pose.bones[profile[key]]
        world=rig.matrix_world @ bone.matrix;head=world.translation.copy()
        rx=Matrix.Rotation(math.radians(x),4,'X')
        ry=Matrix.Rotation(math.radians(y),4,'Y')
        rz=Matrix.Rotation(math.radians(z),4,'Z')
        rotation=rz @ rx @ ry
        bone.matrix=rig.matrix_world.inverted() @ Matrix.Translation(head) @ rotation @ Matrix.Translation(-head) @ world
        if loc is not None:
            matrix=rig.matrix_world @ bone.matrix
            matrix.translation += Vector(loc) / scale
            bone.matrix=rig.matrix_world.inverted() @ matrix
        bpy.context.view_layer.update()
    def apply_retargeted(values):
        # Parents first, so child world-axis rotations use the parent's updated transform.
        keys=sorted((k for k in values if k in profile),
                    key=lambda k: len(rig.pose.bones[profile[k]].parent_recursive))
        for key in keys:
            rotate(key,**values[key])
        for side,weight in values.get('_level',{}).items():
            index=0 if side=='l' else 1
            bone=rig.pose.bones[profile['hand'+side.upper()]]
            world=rig.matrix_world @ bone.matrix
            axis=(world @ hand_local[index]).to_3x3().col[0].normalized()
            rotation=axis.rotation_difference(Vector((0,0,1)))
            rotation=Quaternion().slerp(rotation,weight).to_matrix().to_4x4()
            head=world.translation.copy()
            bone.matrix=rig.matrix_world.inverted() @ Matrix.Translation(head) @ rotation @ Matrix.Translation(-head) @ world
            bpy.context.view_layer.update()
    def pose(clip,t,duration):
        for b in rig.pose.bones: b.matrix_basis=resting[b.name].copy()
        bpy.context.view_layer.update()
        apply_retargeted(retargeted_poses.pose_at(clip,t,duration))
    # Calibrate wrist frames in the ready pose used by the adapted choreography.
    apply_retargeted(retargeted_poses.base_pose())
    # Use calibrated wrist-local frames where rigs do not have individual finger bones.
    # These offsets are explicit approximations to be checked with in-game weapons.
    hand_local=[]
    for side in ('L','R'):
        hand=rig.pose.bones[profile['hand'+side]]
        world=rig.matrix_world @ hand.matrix
        frame=Matrix(((0,0,1,0),(0,-1,0,0),(1,0,0,0),(0,0,0,1)))
        frame.translation=world.translation+Vector((0,0,-height*.025))
        hand_local.append(world.inverted() @ frame)
    # Export the same base rest pose used above, then bake each animation without welding posed vertices.
    collect_colors=False
    out=args.output_root/base;out.mkdir(parents=True,exist_ok=True)
    (out/(base+'.json')).write_text(json.dumps(encode_mesh(model),separators=(',',':'))+'\n')
    conversion=Matrix(((1,0,0),(0,0,-1),(0,1,0)))
    report=[]
    for name,duration,fps,loop in clips:
        count=round(duration*fps)+(0 if loop else 1)
        frames=[];anchors=[]
        for i in range(count):
            pose(name,i/fps,duration)
            p,tris,_=evaluated()
            if tris!=triangles or len(p)!=len(points): raise ValueError('Topology changed during animation')
            frames.append(coords(p)[used])
            hands=[]
            for side,local in zip(('L','R'),hand_local):
                frame=rig.matrix_world @ rig.pose.bones[profile['hand'+side]].matrix @ local
                q=(conversion @ frame.to_3x3()).to_quaternion().normalized()
                hands.append([*coords(np.array(frame.translation)).tolist(),q.w,q.x,q.y,q.z])
            anchors.append(hands)
        frames=np.array(frames)
        if abs(frames).max()>32767: raise ValueError('Clip exceeds int16')
        data=struct.pack('<4sBBBBHH',b'AGTC',2,fps,loop,2,len(used),count)+frames.astype('<i2').tobytes()+np.array(anchors,dtype='<f4').tobytes()
        (out/(base+'_'+name+'.bin')).write_bytes(data)
        report.append(dict(clip=name,frames=count,vertices=len(used),bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),
                           maxDisplacement=int(abs(frames-base_vertices[used]).max())))
    credit=(args.source_root/'assets/v2'/base/'ATTRIBUTION.txt').read_text()
    credit+='\nAnimation trial: retained source skin weights, mapped humanoid bones, exported procedural idle/walk/attack clips and approximate wrist-local hand anchors. Rebuilt matching vertex-colour geometry without pose-dependent welding.\n'
    if motion == 'esdeath':
        credit+='Adapted Esdeath pose timing and body choreography from girl_poses.py/export_girl.py to the mapped source rig. Finger curls and secondary hair/cloth motion are omitted.\n'
    if base == 'tatsumaki':
        credit+='Removed the duplicate inverted green outline shell to expose the textured dress.\n'
    (out/'ATTRIBUTION.txt').write_text(credit)
    (out/'animation-report.json').write_text(json.dumps(dict(character=base,motion=motion,source=source,bones=profile,vertices=len(used),faces=len(faces),clips=report,originalCounts=original_counts,reductionPasses=reduction_passes,ownWeapon=settings.get('ownWeapon',False),
        handAnchors='Wrist-local approximation; weapon grip requires in-game inspection',review='Pending visual and in-game validation'),indent=2))
    print('ANIMATION_RESULT',base,len(used),len(faces),report)

results={}
for base in args.characters:
    try:
        export(base)
        results[base]={'status':'exported; review pending'}
    except Exception as error:
        if not args.continue_on_error: raise
        results[base]={'status':'skipped','reason':str(error)}
        print('ANIMATION_SKIP',base,str(error))
args.output_root.mkdir(parents=True,exist_ok=True)
(args.output_root/'batch-results.json').write_text(json.dumps(results,indent=2)+'\n')
