# Per-girl pose data for export_girl.py (and rig_rory.py, which needs Rory's base pose to place her
# halberd). Pure Python so it can be imported anywhere.
#
# A pose is {bone name: {'x', 'y', 'z': degrees about armature axes, 'loc': (x, y, z) offset}} plus the
# optional '_curl' (finger curl degrees per hand), '_level' (weight per hand for turning the hand so
# its weapon axis points up) and '_aim_l' / '_aim_r' ({'x', 'y', 'z': world direction, 'w': weight}
# for turning a hand so its weapon axis points along a direction; blender axes, -y is forward).
# Attack keys are (frame at 24 fps, pose added on top of the base pose).


def base_pose(girl):
    if girl == 'rory':
        # right forearm forward holding the halberd upright at her side, left hand relaxed lower
        return {
            '_curl': {'l': 30.0, 'r': 50.0},
            'Left arm': {'y': 45.0, 'x': 8.0}, 'Right arm': {'y': -40.0, 'x': 5.0},
            'Left elbow': {'x': -30.0}, 'Right elbow': {'x': -75.0},
        }
    # upper arms hanging, forearms forward with thumbs up: a bow or staff stands upright in the fist
    return {
        '_curl': {'l': 50.0, 'r': 50.0},
        'Left arm': {'y': 40.0, 'x': 5.0}, 'Right arm': {'y': -40.0, 'x': 5.0},
        'Left elbow': {'x': -75.0}, 'Right elbow': {'x': -75.0},
    }


# ---------------------------------------------------------------- Esdeath
# Melee: a right straight punch with a step, left hand guarding
ESDEATH_PUNCH_FRAMES = 22
ESDEATH_PUNCH_KEYS = [
    (0, {}),
    (5, {'_curl': {'l': 40.0, 'r': 40.0}, 'Right arm': {'x': 30.0, 'y': -5.0}, 'Right elbow': {'x': -40.0},
         'Left arm': {'x': -20.0}, 'Left elbow': {'x': -45.0},
         'Chest': {'z': -18.0}, 'Hips': {'z': -8.0}, 'Spine': {'x': -3.0}}),
    (9, {'_curl': {'l': 40.0, 'r': 40.0}, 'Right arm': {'x': -100.0, 'y': 15.0}, 'Right elbow': {'x': 70.0}, 'Right wrist': {'x': 5.0},
         'Left arm': {'x': -25.0}, 'Left elbow': {'x': -50.0},
         'Chest': {'z': 22.0, 'x': 8.0}, 'Hips': {'z': 12.0, 'loc': (0, -3.0, -1.0)}, 'Spine': {'x': 10.0},
         'Left leg': {'x': -22.0}, 'Left knee': {'x': 20.0}, 'Right leg': {'x': 15.0}, 'Head': {'z': -8.0}}),
    (13, {'_curl': {'l': 40.0, 'r': 40.0}, 'Right arm': {'x': -95.0, 'y': 15.0}, 'Right elbow': {'x': 68.0}, 'Right wrist': {'x': 5.0},
          'Left arm': {'x': -25.0}, 'Left elbow': {'x': -50.0},
          'Chest': {'z': 20.0, 'x': 7.0}, 'Hips': {'z': 11.0, 'loc': (0, -3.0, -1.0)}, 'Spine': {'x': 9.0},
          'Left leg': {'x': -22.0}, 'Left knee': {'x': 20.0}, 'Right leg': {'x': 15.0}, 'Head': {'z': -8.0}}),
    (ESDEATH_PUNCH_FRAMES, {}),
]

# Ranged: raise the bow in the left hand, draw with the right, release
ESDEATH_BOW_FRAMES = 32
_BOW_AIM = {'_level': {'l': 1.0}, 'Left arm': {'x': -95.0, 'y': 2.0}, 'Left elbow': {'x': 70.0},
            'Chest': {'z': 25.0}, 'Hips': {'z': 10.0}, 'Head': {'z': -20.0},
            'Left leg': {'x': -8.0}, 'Right leg': {'x': 8.0}}
ESDEATH_BOW_KEYS = [
    (0, {}),
    (6, dict(_BOW_AIM, **{'Right arm': {'x': -70.0, 'y': 10.0}, 'Right elbow': {'x': 40.0}})),
    (13, dict(_BOW_AIM, **{'Right arm': {'x': -15.0, 'y': 30.0}, 'Right elbow': {'z': 115.0, 'x': 10.0}, 'Right wrist': {'z': 15.0}})),
    (20, dict(_BOW_AIM, **{'Right arm': {'x': -15.0, 'y': 30.0}, 'Right elbow': {'z': 115.0, 'x': 10.0}, 'Right wrist': {'z': 15.0}})),
    (23, dict(_BOW_AIM, **{'Right arm': {'x': -10.0, 'y': 35.0}, 'Right elbow': {'z': 70.0, 'x': 5.0}, 'Right wrist': {'z': -10.0}})),
    (ESDEATH_BOW_FRAMES, {}),
]

# Magic: raise the staff in the right hand, then thrust it forward
ESDEATH_STAFF_FRAMES = 30
ESDEATH_STAFF_KEYS = [
    (0, {}),
    (6, {'_level': {'r': 1.0}, 'Right arm': {'x': -135.0, 'y': 5.0}, 'Right elbow': {'x': 55.0},
         'Chest': {'z': -12.0}, 'Hips': {'z': -5.0}, 'Spine': {'x': -4.0}}),
    (11, {'_level': {'r': 1.0}, 'Right arm': {'x': -95.0, 'y': 2.0}, 'Right elbow': {'x': 72.0}, 'Right wrist': {'x': 5.0},
          'Chest': {'z': 16.0, 'x': 8.0}, 'Hips': {'z': 10.0, 'loc': (0, -2.0, -0.8)}, 'Spine': {'x': 10.0},
          'Left arm': {'x': 20.0}, 'Head': {'x': -6.0, 'z': -6.0},
          'Left leg': {'x': 10.0}, 'Right leg': {'x': -12.0}, 'Right knee': {'x': 14.0}}),
    (18, {'_level': {'r': 1.0}, 'Right arm': {'x': -90.0, 'y': 2.0}, 'Right elbow': {'x': 70.0}, 'Right wrist': {'x': 5.0},
          'Chest': {'z': 14.0, 'x': 6.0}, 'Hips': {'z': 9.0, 'loc': (0, -2.0, -0.6)}, 'Spine': {'x': 8.0},
          'Left arm': {'x': 18.0}, 'Head': {'x': -4.0, 'z': -5.0},
          'Left leg': {'x': 10.0}, 'Right leg': {'x': -12.0}, 'Right knee': {'x': 14.0}}),
    (ESDEATH_STAFF_FRAMES, {}),
]

# ---------------------------------------------------------------- Rory
def _aim(x, y, z):
    return {'x': x, 'y': y, 'z': z, 'w': 1.0}


# Melee: lift the halberd up and back over her right shoulder, then chop down in front of her
RORY_CHOP_FRAMES = 26
_CHOP_UP = {'_aim_r': _aim(0.15, 0.75, 0.65), 'Right arm': {'x': -150.0, 'y': 25.0}, 'Right elbow': {'x': 40.0},
            'Left arm': {'x': -30.0}, 'Left elbow': {'x': -40.0},
            'Chest': {'z': -20.0, 'x': -6.0}, 'Hips': {'z': -8.0}, 'Spine': {'x': -5.0}, 'Head': {'x': -5.0},
            'Right leg': {'x': 6.0}}
_CHOP_DOWN = {'_aim_r': _aim(0.0, -0.75, -0.65), 'Right arm': {'x': -70.0, 'y': 20.0}, 'Right elbow': {'x': 60.0},
              'Left arm': {'x': -40.0}, 'Left elbow': {'x': -50.0},
              'Chest': {'z': 18.0, 'x': 14.0}, 'Hips': {'z': 10.0, 'loc': (0, -4.0, -2.5)}, 'Spine': {'x': 12.0},
              'Left leg': {'x': -25.0}, 'Left knee': {'x': 25.0}, 'Right leg': {'x': 20.0}, 'Right knee': {'x': 10.0},
              'Head': {'x': 6.0}}
RORY_CHOP_KEYS = [
    (0, {}),
    (8, _CHOP_UP),
    (12, _CHOP_DOWN),
    (17, _CHOP_DOWN),
    (RORY_CHOP_FRAMES, {}),
]

# Ranged: level the halberd and lunge, driving the spike forward
RORY_THRUST_FRAMES = 26
_THRUST_BACK = {'_aim_r': _aim(0.0, -1.0, 0.1), 'Right arm': {'x': 10.0, 'y': 30.0}, 'Right elbow': {'x': 10.0},
                'Left arm': {'x': -60.0, 'y': -10.0}, 'Left elbow': {'x': -30.0},
                'Chest': {'z': -25.0}, 'Hips': {'z': -12.0}, 'Spine': {'x': -4.0}, 'Head': {'z': 15.0}}
_THRUST_OUT = {'_aim_r': _aim(0.0, -1.0, 0.05), 'Right arm': {'x': -70.0, 'y': 25.0}, 'Right elbow': {'x': 75.0},
               'Left arm': {'x': -80.0, 'y': -10.0}, 'Left elbow': {'x': -10.0},
               'Chest': {'z': 10.0, 'x': 10.0}, 'Hips': {'z': 6.0, 'loc': (0, -6.0, -3.0)}, 'Spine': {'x': 10.0},
               'Left leg': {'x': -35.0}, 'Left knee': {'x': 30.0}, 'Right leg': {'x': 25.0}, 'Head': {'z': -5.0}}
RORY_THRUST_KEYS = [
    (0, {}),
    (7, _THRUST_BACK),
    (11, _THRUST_OUT),
    (16, _THRUST_OUT),
    (RORY_THRUST_FRAMES, {}),
]

# Magic: raise the halberd high overhead, then slam its butt into the ground
RORY_SLAM_FRAMES = 30
_SLAM_UP = {'_aim_r': _aim(0.0, -0.1, 1.0), 'Right arm': {'x': -165.0, 'y': 10.0}, 'Right elbow': {'x': 60.0},
            'Left arm': {'x': -20.0, 'y': -20.0}, 'Left elbow': {'x': -20.0},
            'Chest': {'x': -10.0}, 'Spine': {'x': -6.0}, 'Head': {'x': -12.0}, 'Hips': {'loc': (0, 0, 2.0)},
            'Left knee': {'x': 5.0}}
_SLAM_DOWN = {'_aim_r': _aim(0.0, -0.1, 1.0), 'Right arm': {'x': -25.0, 'y': 0.0}, 'Right elbow': {'x': 10.0},
              'Left arm': {'x': -10.0, 'y': -30.0}, 'Left elbow': {'x': -20.0},
              'Chest': {'x': 12.0}, 'Spine': {'x': 8.0}, 'Head': {'x': 8.0}, 'Hips': {'loc': (0, -2.0, -6.0)},
              'Left leg': {'x': -20.0}, 'Left knee': {'x': 35.0}, 'Right leg': {'x': -20.0}, 'Right knee': {'x': 35.0},
              'Left ankle': {'x': -15.0}, 'Right ankle': {'x': -15.0}}
RORY_SLAM_KEYS = [
    (0, {}),
    (10, _SLAM_UP),
    (14, _SLAM_DOWN),
    (20, _SLAM_DOWN),
    (RORY_SLAM_FRAMES, {}),
]

# Attack clips per girl: clip name -> (keys, whip centre frame, whip amplitude). The clip length is the
# last key's frame.
ATTACKS = {
    'esdeath': {
        'attack_melee': (ESDEATH_PUNCH_KEYS, 11.0, 8.0),
        'attack_ranged': (ESDEATH_BOW_KEYS, 23.0, 4.0),
        'attack_magic': (ESDEATH_STAFF_KEYS, 13.0, 10.0),
    },
    'rory': {
        'attack_melee': (RORY_CHOP_KEYS, 13.0, 8.0),
        'attack_ranged': (RORY_THRUST_KEYS, 12.0, 6.0),
        'attack_magic': (RORY_SLAM_KEYS, 15.0, 10.0),
    },
}
