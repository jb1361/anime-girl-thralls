"""Esdeath choreography adapted to semantic humanoid joints.

Attack keys remain in girl_poses.py, shared with the original exporter. Finger and
secondary-motion chains are deliberately omitted until their mappings are reviewed.
Translations are in normalized 200-unit model space, not source-file units.
"""
import math
import girl_poses

JOINTS = {
    'Hips': 'hips', 'Spine': 'spine', 'Chest': 'chest', 'Head': 'head',
    'Left arm': 'armL', 'Left elbow': 'elbowL', 'Left wrist': 'handL',
    'Right arm': 'armR', 'Right elbow': 'elbowR', 'Right wrist': 'handR',
    'Left leg': 'legL', 'Left knee': 'kneeL', 'Left ankle': 'footL',
    'Right leg': 'legR', 'Right knee': 'kneeR', 'Right ankle': 'footR',
}
CLIPS = [('idle', 3.0, 8, True), ('walk', 28/24, 12, True)] + [
    (name, keys[-1][0]/24, 12, False)
    for name, (keys, _, _) in girl_poses.ATTACKS['esdeath'].items()
]

def add(pose, name, **values):
    bone = pose.setdefault(name, {})
    for key, value in values.items():
        if key == 'loc':
            bone[key] = tuple(a+b for a,b in zip(bone.get(key, (0,0,0)), value))
        else:
            bone[key] = bone.get(key, 0) + value

def base_pose():
    # Upper arms are already lowered by the rig adapter; do not reapply MMD T-pose offsets.
    return {'armL': {'x': 5}, 'armR': {'x': 5},
            'elbowL': {'x': -65}, 'elbowR': {'x': -65}}

def pose_at(clip, time, duration):
    pose = base_pose()
    phase = 2 * math.pi * time / duration
    s, c = math.sin(phase), math.cos(phase)
    if clip == 'idle':
        add(pose, 'chest', x=1.5*s)
        add(pose, 'spine', x=.5*s)
        add(pose, 'hips', loc=(0,0,.6*s))
        add(pose, 'head', z=4*math.sin(phase+1), x=1.5*math.sin(phase+2))
        for side, sign in [('L',1), ('R',-1)]:
            add(pose, 'arm'+side, y=sign*2*s)
            add(pose, 'elbow'+side, x=3*s)
    elif clip == 'walk':
        for side, sign in [('L',1), ('R',-1)]:
            knee = 65*max(0,sign*c)
            add(pose, 'leg'+side, x=-sign*40*s)
            add(pose, 'knee'+side, x=knee)
            add(pose, 'foot'+side, x=-.35*knee)
            add(pose, 'arm'+side, x=sign*30*s)
            add(pose, 'elbow'+side, x=-sign*8*s)
        add(pose, 'hips', loc=(0,0,1.4*math.cos(2*phase)), z=7*s)
        add(pose, 'chest', z=-8*s, x=2*math.cos(2*phase))
        add(pose, 'spine', x=5)
        add(pose, 'head', z=3*s)
    else:
        keys = girl_poses.ATTACKS['esdeath'][clip][0]
        frame = min(keys[-1][0], max(0,time*24))
        for (start,a),(end,b) in zip(keys,keys[1:]):
            if frame > end:
                continue
            u = min(1,max(0,(frame-start)/(end-start)))
            u = u*u*(3-2*u)
            for name in set(a) | set(b):
                if name not in JOINTS and name != '_level':
                    continue
                values = {}
                for key in set(a.get(name,{})) | set(b.get(name,{})):
                    zero = (0,0,0) if key == 'loc' else 0
                    va, vb = a.get(name,{}).get(key,zero), b.get(name,{}).get(key,zero)
                    values[key] = tuple(x+(y-x)*u for x,y in zip(va,vb)) if key == 'loc' else va+(vb-va)*u
                if name == '_level':
                    pose[name] = values
                else:
                    add(pose, JOINTS[name], **values)
            break
    return pose
