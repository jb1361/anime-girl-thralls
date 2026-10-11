"""Align an RS item inventory model into a canonical hand frame from its shape alone.

Hand frame: x = fist axis (little finger -> index finger, i.e. "up" when the thumb is up),
y = wrist -> knuckles, z = x cross y. Weapons are placed with their long axis along x; a bow's belly
(the side away from the string) faces +y; a staff's heavier end (the head) points to +x.
Coordinates are RS model units (x east, y up negative, z north) as read from the cache.
"""
import numpy as np


def read_obj(path):
    v, f = [], []
    for line in open(path):
        p = line.split()
        if not p:
            continue
        if p[0] == 'v':
            v.append([float(p[1]), float(p[2]), float(p[3])])
        elif p[0] == 'f':
            f.append([int(a.split('/')[0]) - 1 for a in p[1:4]])
    return np.array(v, dtype=np.float64), np.array(f, dtype=np.int32)


def align(verts, kind):
    """Returns (hand_space_vertices, info). kind: 'bow', 'staff' or 'melee'."""
    c = verts.mean(axis=0)
    centered = verts - c
    cov = centered.T @ centered / len(verts)
    w, vecs = np.linalg.eigh(cov)
    order = np.argsort(w)[::-1]
    e1, e2, e3 = vecs[:, order[0]], vecs[:, order[1]], vecs[:, order[2]]

    along = centered @ e1
    lo, hi = along.min(), along.max()
    length = hi - lo
    perp = centered - np.outer(along, e1)
    info = {'length': length}

    if kind == 'bow':
        # The tips lie on the string chord; the limbs and grip bulge toward the belly
        tips = np.concatenate([perp[along > hi - 0.08 * length], perp[along < lo + 0.08 * length]])
        chord_mid = tips.mean(axis=0)
        belly = perp.mean(axis=0) - chord_mid
        belly -= belly.dot(e1) * e1
        n = np.linalg.norm(belly)
        if n < 1e-6:
            belly = e2
        else:
            belly /= n
        info['belly_offset'] = n
        x_axis, y_axis = e1, belly
        grip = c + e1 * (lo + hi) / 2  # bows are gripped at their centre
    else:
        # The head is the end with the larger cross section
        r = np.linalg.norm(perp, axis=1)
        top_r = r[along > hi - 0.25 * length].mean()
        bot_r = r[along < lo + 0.25 * length].mean()
        head_sign = 1.0 if top_r >= bot_r else -1.0
        x_axis = e1 * head_sign
        y_axis = e2
        info['head_ratio'] = top_r / max(bot_r, 1e-6)
        if kind == 'staff':
            # held a little below the middle, head up
            grip = c + e1 * (lo + 0.42 * length if head_sign > 0 else hi - 0.42 * length)
        else:
            # melee: gripped near the light end (handle)
            grip = c + e1 * (lo + 0.12 * length if head_sign > 0 else hi - 0.12 * length)

    y_axis = y_axis - y_axis.dot(x_axis) * x_axis
    y_axis /= np.linalg.norm(y_axis)
    z_axis = np.cross(x_axis, y_axis)
    R = np.stack([x_axis, y_axis, z_axis])  # rows: hand axes in model space
    hand = (verts - grip) @ R.T
    info['extent_x'] = (hand[:, 0].min(), hand[:, 0].max())
    return hand, info


if __name__ == '__main__':
    import sys
    for path, kind in zip(sys.argv[1::2], sys.argv[2::2]):
        v, f = read_obj(path)
        h, info = align(v, kind)
        print(path.split('/')[-1], kind, 'verts', len(v), 'bounds', v.min(axis=0).astype(int), v.max(axis=0).astype(int),
              {k: (np.round(val, 2) if not isinstance(val, tuple) else tuple(np.round(val, 1))) for k, val in info.items()})
