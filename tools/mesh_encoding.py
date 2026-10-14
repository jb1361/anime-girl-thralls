"""Lossless geometry storage: delta-v1 uses differences per column from the previous row."""


def encode_mesh(mesh):
    if mesh.get("geometryEncoding"):
        raise ValueError("Mesh is already encoded")
    encoded = dict(mesh, geometryEncoding="delta-v1")
    for field in ("vertices", "faces"):
        previous = [0, 0, 0]
        rows = []
        for row in mesh[field]:
            rows.append([value - previous[i] for i, value in enumerate(row)])
            previous = row
        encoded[field] = rows
    return encoded


def decode_mesh(mesh):
    if "geometryEncoding" not in mesh:
        return mesh
    if mesh["geometryEncoding"] != "delta-v1":
        raise ValueError("Unsupported geometry encoding")
    decoded = dict(mesh)
    del decoded["geometryEncoding"]
    for field in ("vertices", "faces"):
        previous = [0, 0, 0]
        rows = []
        for row in mesh[field]:
            previous = [value + previous[i] for i, value in enumerate(row)]
            rows.append(previous)
        decoded[field] = rows
    return decoded
