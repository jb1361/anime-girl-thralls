"""Offline hand calibration and secondary motion for explicitly reviewed bones."""
import math
import bpy
from mathutils import Matrix, Quaternion, Vector


def turn(rig, bone, axis, degrees):
    world = rig.matrix_world @ bone.matrix
    head = world.translation.copy()
    rotation = Quaternion(axis.normalized(), math.radians(degrees)).to_matrix().to_4x4()
    bone.matrix = rig.matrix_world.inverted() @ Matrix.Translation(head) @ rotation @ Matrix.Translation(-head) @ world
    bpy.context.view_layer.update()


class RigDetails:
    def __init__(self, rig, profile, settings, objects):
        self.rig = rig
        self.hands = []
        self.hair = settings.get('hairChains', [])
        for chain in self.hair:
            for name in chain:
                if name not in rig.pose.bones:
                    raise ValueError('Missing hair bone: ' + name)
        for side in ('L', 'R'):
            bone = rig.pose.bones[profile['hand' + side]]
            world = rig.matrix_world @ bone.matrix
            wrist = world.translation
            detail = settings.get('hands', {}).get(side, {})
            chains = detail.get('fingers', [])
            for name in [n for chain in chains for n in chain] + detail.get('landmarks', []):
                if name not in rig.pose.bones:
                    raise ValueError('Missing hand bone: ' + name)
            if detail.get('landmarks'):
                middle, index, little = [rig.matrix_world @ rig.pose.bones[n].head for n in detail['landmarks']]
                forward = (middle - wrist).normalized()
                across = index - little
                across = (across - forward * across.dot(forward)).normalized()
                normal = across.cross(forward).normalized()
                sign = detail['curlSign']
                # Place the handle inside the closing fingers, on the palm side.
                origin = wrist.lerp(middle, .85) + normal * sign * (middle - wrist).length * detail.get('palmDepth', .25)
                frame = Matrix((across, forward, normal)).transposed()
                up = Vector((0, 0, 1))
                roll = math.atan2(up.dot(forward), up.dot(across))
                frame = frame @ Matrix.Rotation(roll, 3, 'Z')
                frame = frame.to_4x4()
                frame.translation = origin
                axis = world.to_3x3().inverted() @ across
            else:
                # Closed, single-bone hands: use the weighted hand surface, never display tails.
                samples = []
                dg = bpy.context.evaluated_depsgraph_get()
                for obj in objects:
                    group = obj.vertex_groups.get(bone.name)
                    if group is None:
                        continue
                    evaluated = obj.evaluated_get(dg)
                    mesh = evaluated.to_mesh()
                    samples.extend(evaluated.matrix_world @ v.co for v in mesh.vertices
                                   if any(g.group == group.index and g.weight > .5 for g in v.groups))
                    evaluated.to_mesh_clear()
                if not samples:
                    raise ValueError('No weighted hand surface: ' + bone.name)
                frame = Matrix(((0,0,1,0),(0,-1,0,0),(1,0,0,0),(0,0,0,1)))
                frame.translation = sum(samples, Vector()) / len(samples)
                axis, sign = None, 0
            self.hands.append((bone, world.inverted() @ frame, chains, axis, detail.get('curlSign', sign), detail.get('curlScale', 1)))

    @property
    def anchors(self):
        return [hand[1] for hand in self.hands]

    def apply(self, values, clip, time, duration):
        for side, (hand, _, chains, local_axis, sign, strength) in zip(('l', 'r'), self.hands):
            if not chains:
                continue
            axis = (self.rig.matrix_world @ hand.matrix).to_3x3() @ local_axis
            # Keep a handle-sized opening; original melee tightening is intentionally capped.
            curl = min(65, 42 + values.get('_curl', {}).get(side, 0) * .35)
            for chain in chains:
                for depth, name in enumerate(chain):
                    turn(self.rig, self.rig.pose.bones[name], axis, sign * curl * strength * (1 if depth else .85))
        phase = 2 * math.pi * time / duration
        loop = clip in ('idle', 'walk')
        envelope = 1 if loop else math.sin(math.pi * min(1, max(0, time / duration))) ** 2
        amplitude = 2 if clip == 'idle' else 4
        for chain_index, chain in enumerate(self.hair):
            for depth, name in enumerate(chain):
                bone = self.rig.pose.bones[name]
                # Small local rotations, lagged down each chain; attacks settle exactly at both ends.
                angle = amplitude * envelope * math.sin(phase - depth * .55 + chain_index * .7)
                bone.rotation_mode = 'QUATERNION'
                bone.rotation_quaternion = bone.rotation_quaternion @ Quaternion((1,0,0), math.radians(angle))
        bpy.context.view_layer.update()
