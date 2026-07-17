package com.slopeconnector.hotfix;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ConvexGeometry {
    private static final double EPS = 1.0E-6;
    private static final double SURFACE_EPS = 2.0E-4;

    record Plane(Vec3d n, double d) { double side(Vec3d p) { return n.dotProduct(p) - d; } }
    record Poly(List<List<Vec3d>> faces) {}
    record SurfaceTriangle(float[] xyz, boolean cutFace) {}

    private ConvexGeometry() {}

    static Poly cube(BlockPos p) {
        double x = p.getX(), y = p.getY(), z = p.getZ();
        Vec3d v000 = new Vec3d(x, y, z), v100 = new Vec3d(x + 1, y, z);
        Vec3d v110 = new Vec3d(x + 1, y + 1, z), v010 = new Vec3d(x, y + 1, z);
        Vec3d v001 = new Vec3d(x, y, z + 1), v101 = new Vec3d(x + 1, y, z + 1);
        Vec3d v111 = new Vec3d(x + 1, y + 1, z + 1), v011 = new Vec3d(x, y + 1, z + 1);
        return new Poly(new ArrayList<>(List.of(
                new ArrayList<>(List.of(v000, v010, v110, v100)),
                new ArrayList<>(List.of(v001, v101, v111, v011)),
                new ArrayList<>(List.of(v000, v001, v011, v010)),
                new ArrayList<>(List.of(v100, v110, v111, v101)),
                new ArrayList<>(List.of(v000, v100, v101, v001)),
                new ArrayList<>(List.of(v010, v011, v111, v110))
        )));
    }

    static Poly prism(float[] xyz) {
        Vec3d[] v = new Vec3d[8];
        for (int i = 0; i < 8; i++) v[i] = new Vec3d(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]);
        List<List<Vec3d>> faces = new ArrayList<>(List.of(
                new ArrayList<>(List.of(v[0], v[4], v[5], v[1])),
                new ArrayList<>(List.of(v[3], v[2], v[6], v[7])),
                new ArrayList<>(List.of(v[0], v[3], v[7], v[4])),
                new ArrayList<>(List.of(v[1], v[5], v[6], v[2])),
                new ArrayList<>(List.of(v[0], v[1], v[2], v[3])),
                new ArrayList<>(List.of(v[4], v[7], v[6], v[5]))
        ));
        Vec3d center = Vec3d.ZERO;
        for (Vec3d q : v) center = center.add(q);
        center = center.multiply(1.0 / 8.0);
        for (int i = 0; i < faces.size(); i++) faces.set(i, orientOutward(faces.get(i), center));
        return new Poly(faces);
    }

    static Box bounds(float[] xyz) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            minX = Math.min(minX, xyz[i * 3]); maxX = Math.max(maxX, xyz[i * 3]);
            minY = Math.min(minY, xyz[i * 3 + 1]); maxY = Math.max(maxY, xyz[i * 3 + 1]);
            minZ = Math.min(minZ, xyz[i * 3 + 2]); maxZ = Math.max(maxZ, xyz[i * 3 + 2]);
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    static Box bounds(Poly poly) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (List<Vec3d> face : poly.faces) for (Vec3d p : face) {
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
            minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    static Poly intersection(Poly subject, Poly cutter) {
        Poly inside = subject;
        for (Plane plane : planes(cutter)) {
            inside = clip(inside, plane, true);
            if (inside == null || volume(inside) <= 1.0E-10) return null;
        }
        return inside;
    }

    static List<Poly> subtract(List<Poly> subjects, Poly cutter) {
        List<Poly> result = new ArrayList<>();
        for (Poly subject : subjects) result.addAll(subtractOne(subject, cutter));
        result.removeIf(poly -> volume(poly) <= 1.0E-9);
        return result;
    }

    private static List<Poly> subtractOne(Poly subject, Poly cutter) {
        Poly inside = subject;
        List<Poly> outsidePieces = new ArrayList<>();
        for (Plane plane : planes(cutter)) {
            Poly outside = clip(inside, plane, false);
            if (outside != null && volume(outside) > 1.0E-9) outsidePieces.add(outside);
            inside = clip(inside, plane, true);
            if (inside == null || volume(inside) <= 1.0E-10) break;
        }
        return outsidePieces;
    }

    static Poly clip(Poly poly, Plane plane, boolean keepInside) {
        if (poly == null) return null;
        List<List<Vec3d>> faces = new ArrayList<>();
        List<Vec3d> cap = new ArrayList<>();
        for (List<Vec3d> face : poly.faces) {
            List<Vec3d> clipped = clipPolygon(face, plane, keepInside, cap);
            clipped = removeCollinear(clipped);
            if (clipped.size() >= 3 && polygonArea(clipped) > 1.0E-10) faces.add(clipped);
        }
        List<Vec3d> unique = unique(cap);
        if (unique.size() >= 3) {
            Vec3d desired = keepInside ? plane.n : plane.n.multiply(-1);
            List<Vec3d> sorted = removeCollinear(sortCap(unique, desired));
            if (sorted.size() >= 3 && polygonArea(sorted) > 1.0E-10) faces.add(sorted);
        }
        return faces.isEmpty() ? null : new Poly(faces);
    }

    private static List<Vec3d> clipPolygon(List<Vec3d> input, Plane plane,
                                           boolean keepInside, List<Vec3d> cap) {
        List<Vec3d> out = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            Vec3d a = input.get(i), b = input.get((i + 1) % input.size());
            double da = plane.side(a), db = plane.side(b);
            boolean inA = keepInside ? da <= EPS : da >= -EPS;
            boolean inB = keepInside ? db <= EPS : db >= -EPS;
            if (inA) out.add(a);
            if (inA != inB) {
                double denominator = da - db;
                if (Math.abs(denominator) < 1.0E-12) continue;
                double t = da / denominator;
                Vec3d p = a.add(b.subtract(a).multiply(t));
                out.add(p);
                cap.add(p);
            }
        }
        return unique(out);
    }

    static List<Plane> planes(Poly poly) {
        List<Plane> out = new ArrayList<>();
        for (List<Vec3d> face : poly.faces) {
            if (face.size() < 3) continue;
            Vec3d n = faceNormal(face);
            if (n.lengthSquared() < 1.0E-16) continue;
            out.add(new Plane(n, n.dotProduct(face.get(0))));
        }
        return out;
    }

    static boolean contains(Poly poly, Vec3d p) {
        for (Plane plane : planes(poly)) if (plane.side(p) > 1.0E-5) return false;
        return true;
    }

    static double volume(Poly poly) {
        if (poly == null) return 0.0;
        double sum = 0.0;
        for (List<Vec3d> face : poly.faces) {
            if (face.size() < 3) continue;
            Vec3d a = face.get(0);
            for (int i = 1; i < face.size() - 1; i++) {
                Vec3d b = face.get(i), c = face.get(i + 1);
                sum += a.dotProduct(b.crossProduct(c)) / 6.0;
            }
        }
        return Math.abs(sum);
    }

    /**
     * Builds the exact visible boundary of cube minus swept prisms.
     *
     * Outer faces come only from the original cube planes. Cut faces come directly from the first
     * four side faces of each swept prism; prism start/end caps are deliberately excluded because
     * adjacent arc segments share those caps and rendering them caused the black flashing sheets.
     */
    static List<SurfaceTriangle> boundaryTriangles(List<Poly> pieces, BlockPos origin,
                                                   List<Poly> cutters) {
        List<SurfaceTriangle> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1) Original block exterior. CSG partition faces are never on a cube boundary, so they are
        // excluded completely instead of relying on a fragile face-centre adjacency test.
        for (Poly piece : pieces) {
            Vec3d pieceCenter = centroid(piece);
            for (List<Vec3d> rawFace : piece.faces) {
                List<Vec3d> face = removeCollinear(unique(rawFace));
                if (face.size() < 3 || !liesOnCubeBoundary(face, origin)) continue;
                face = orientOutward(face, pieceCenter);
                appendTriangles(out, seen, face, origin, false);
            }
        }

        // 2) Exact arc/block interface. A generated swept prism always stores side faces first and
        // segment caps last. The interface normal points into the removed prism, opposite the
        // prism's own outward normal.
        if (cutters != null) {
            for (Poly cutter : cutters) {
                int sideCount = Math.min(4, cutter.faces.size());
                for (int faceIndex = 0; faceIndex < sideCount; faceIndex++) {
                    List<Vec3d> face = clipFaceToCube(cutter.faces.get(faceIndex), origin);
                    face = removeCollinear(unique(face));
                    if (face.size() < 3 || polygonArea(face) <= 1.0E-10) continue;

                    Vec3d cutterOut = faceNormal(face);
                    if (cutterOut.lengthSquared() < 1.0E-16) continue;
                    Vec3d cutNormal = cutterOut.multiply(-1.0);
                    if (faceNormal(face).dotProduct(cutNormal) < 0.0) {
                        List<Vec3d> reversed = new ArrayList<>(face);
                        Collections.reverse(reversed);
                        face = reversed;
                    }

                    Vec3d center = faceCenter(face);
                    Vec3d solidProbe = center.subtract(cutNormal.multiply(SURFACE_EPS));
                    Vec3d removedProbe = center.add(cutNormal.multiply(SURFACE_EPS));
                    if (!containedInAny(pieces, solidProbe)) continue;
                    if (!containedInAny(cutters, removedProbe)) continue;
                    if (containedInAnother(cutters, cutter, solidProbe)) continue;

                    appendTriangles(out, seen, face, origin, true);
                }
            }
        }
        return out;
    }

    private static void appendTriangles(List<SurfaceTriangle> out, Set<String> seen,
                                        List<Vec3d> face, BlockPos origin, boolean cutFace) {
        Vec3d a = face.get(0);
        for (int i = 1; i < face.size() - 1; i++) {
            Vec3d b = face.get(i), c = face.get(i + 1);
            if (triangleArea(a, b, c) <= 1.0E-10) continue;
            String key = triangleKey(a, b, c, cutFace);
            if (!seen.add(key)) continue;
            out.add(new SurfaceTriangle(new float[]{
                    (float)(a.x - origin.getX()), (float)(a.y - origin.getY()), (float)(a.z - origin.getZ()),
                    (float)(b.x - origin.getX()), (float)(b.y - origin.getY()), (float)(b.z - origin.getZ()),
                    (float)(c.x - origin.getX()), (float)(c.y - origin.getY()), (float)(c.z - origin.getZ())
            }, cutFace));
        }
    }

    private static List<Vec3d> clipFaceToCube(List<Vec3d> input, BlockPos origin) {
        List<Vec3d> face = new ArrayList<>(input);
        face = clipFaceAxis(face, 0, origin.getX(), true);
        face = clipFaceAxis(face, 0, origin.getX() + 1.0, false);
        face = clipFaceAxis(face, 1, origin.getY(), true);
        face = clipFaceAxis(face, 1, origin.getY() + 1.0, false);
        face = clipFaceAxis(face, 2, origin.getZ(), true);
        face = clipFaceAxis(face, 2, origin.getZ() + 1.0, false);
        return face;
    }

    private static List<Vec3d> clipFaceAxis(List<Vec3d> input, int axis,
                                            double boundary, boolean keepGreater) {
        if (input.isEmpty()) return input;
        List<Vec3d> out = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            Vec3d a = input.get(i), b = input.get((i + 1) % input.size());
            double av = axisValue(a, axis), bv = axisValue(b, axis);
            boolean inA = keepGreater ? av >= boundary - EPS : av <= boundary + EPS;
            boolean inB = keepGreater ? bv >= boundary - EPS : bv <= boundary + EPS;
            if (inA) out.add(a);
            if (inA != inB) {
                double denominator = bv - av;
                if (Math.abs(denominator) > 1.0E-12) {
                    double t = (boundary - av) / denominator;
                    out.add(a.add(b.subtract(a).multiply(t)));
                }
            }
        }
        return unique(out);
    }

    private static double axisValue(Vec3d point, int axis) {
        return axis == 0 ? point.x : axis == 1 ? point.y : point.z;
    }

    private static boolean containedInAnother(List<Poly> polys, Poly self, Vec3d point) {
        for (Poly poly : polys) if (poly != self && contains(poly, point)) return true;
        return false;
    }

    static List<float[]> triangulate(List<Poly> pieces, BlockPos origin) {
        List<float[]> out = new ArrayList<>();
        for (Poly p : pieces) for (List<Vec3d> face : p.faces) {
            if (face.size() < 3) continue;
            Vec3d a = face.get(0);
            for (int i = 1; i < face.size() - 1; i++) {
                Vec3d b = face.get(i), c = face.get(i + 1);
                out.add(new float[]{
                        (float)(a.x-origin.getX()), (float)(a.y-origin.getY()), (float)(a.z-origin.getZ()),
                        (float)(b.x-origin.getX()), (float)(b.y-origin.getY()), (float)(b.z-origin.getZ()),
                        (float)(c.x-origin.getX()), (float)(c.y-origin.getY()), (float)(c.z-origin.getZ())});
            }
        }
        return out;
    }

    static List<float[]> triangulateVisible(List<Poly> pieces, BlockPos origin, List<Plane> hiddenPlanes) {
        List<float[]> out = new ArrayList<>();
        for (Poly p : pieces) for (List<Vec3d> face : p.faces) {
            if (face.size() < 3 || liesOnAnyPlane(face, hiddenPlanes)) continue;
            Vec3d a = face.get(0);
            for (int i = 1; i < face.size() - 1; i++) {
                Vec3d b = face.get(i), c = face.get(i + 1);
                out.add(new float[]{
                        (float)(a.x-origin.getX()), (float)(a.y-origin.getY()), (float)(a.z-origin.getZ()),
                        (float)(b.x-origin.getX()), (float)(b.y-origin.getY()), (float)(b.z-origin.getZ()),
                        (float)(c.x-origin.getX()), (float)(c.y-origin.getY()), (float)(c.z-origin.getZ())});
            }
        }
        return out;
    }

    static Vec3d centroid(Poly poly) {
        List<Vec3d> points = new ArrayList<>();
        for (List<Vec3d> face : poly.faces) points.addAll(face);
        points = unique(points);
        if (points.isEmpty()) return Vec3d.ZERO;
        Vec3d center = Vec3d.ZERO;
        for (Vec3d point : points) center = center.add(point);
        return center.multiply(1.0 / points.size());
    }

    static Poly translated(Poly poly, Vec3d delta) {
        List<List<Vec3d>> faces = new ArrayList<>();
        for (List<Vec3d> face : poly.faces) {
            List<Vec3d> moved = new ArrayList<>();
            for (Vec3d p : face) moved.add(p.add(delta));
            faces.add(moved);
        }
        return new Poly(faces);
    }

    private static boolean containedInAnyOther(List<Poly> pieces, Poly self, Vec3d point) {
        for (Poly piece : pieces) if (piece != self && contains(piece, point)) return true;
        return false;
    }

    private static boolean containedInAny(List<Poly> pieces, Vec3d point) {
        for (Poly piece : pieces) if (contains(piece, point)) return true;
        return false;
    }

    private static boolean liesOnCubeBoundary(List<Vec3d> face, BlockPos origin) {
        double[] values = new double[]{
                origin.getX(), origin.getX() + 1.0,
                origin.getY(), origin.getY() + 1.0,
                origin.getZ(), origin.getZ() + 1.0
        };
        for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 2; side++) {
                double target = values[axis * 2 + side];
                boolean all = true;
                for (Vec3d p : face) {
                    double value = axis == 0 ? p.x : axis == 1 ? p.y : p.z;
                    if (Math.abs(value - target) > 2.0E-5) { all = false; break; }
                }
                if (all) return true;
            }
        }
        return false;
    }

    private static String triangleKey(Vec3d a, Vec3d b, Vec3d c, boolean cut) {
        long[][] q = new long[][]{quantize(a), quantize(b), quantize(c)};
        java.util.Arrays.sort(q, (x, y) -> {
            int cmp = Long.compare(x[0], y[0]);
            if (cmp != 0) return cmp;
            cmp = Long.compare(x[1], y[1]);
            return cmp != 0 ? cmp : Long.compare(x[2], y[2]);
        });
        return (cut ? "C" : "O") + q[0][0] + ',' + q[0][1] + ',' + q[0][2] + ';'
                + q[1][0] + ',' + q[1][1] + ',' + q[1][2] + ';'
                + q[2][0] + ',' + q[2][1] + ',' + q[2][2];
    }

    private static long[] quantize(Vec3d p) {
        return new long[]{Math.round(p.x * 100000.0), Math.round(p.y * 100000.0), Math.round(p.z * 100000.0)};
    }

    private static boolean liesOnAnyPlane(List<Vec3d> face, List<Plane> planeList) {
        if (planeList == null || planeList.isEmpty()) return false;
        for (Plane plane : planeList) {
            boolean all = true;
            for (Vec3d p : face) {
                if (Math.abs(plane.side(p)) > 2.0E-5) { all = false; break; }
            }
            if (all) return true;
        }
        return false;
    }

    private static List<Vec3d> orientOutward(List<Vec3d> face, Vec3d center) {
        Vec3d n = faceNormal(face);
        Vec3d fc = faceCenter(face);
        if (n.dotProduct(fc.subtract(center)) < 0) {
            List<Vec3d> reversed = new ArrayList<>(face);
            Collections.reverse(reversed);
            return reversed;
        }
        return face;
    }

    private static Vec3d faceNormal(List<Vec3d> face) {
        if (face.size() < 3) return Vec3d.ZERO;
        Vec3d n = face.get(1).subtract(face.get(0)).crossProduct(face.get(2).subtract(face.get(0)));
        return n.lengthSquared() < 1.0E-18 ? Vec3d.ZERO : n.normalize();
    }

    private static Vec3d faceCenter(List<Vec3d> face) {
        Vec3d c = Vec3d.ZERO;
        for (Vec3d p : face) c = c.add(p);
        return c.multiply(1.0 / face.size());
    }

    private static double polygonArea(List<Vec3d> face) {
        if (face.size() < 3) return 0.0;
        Vec3d a = face.get(0);
        double area = 0.0;
        for (int i = 1; i < face.size() - 1; i++) area += triangleArea(a, face.get(i), face.get(i + 1));
        return area;
    }

    private static double triangleArea(Vec3d a, Vec3d b, Vec3d c) {
        return b.subtract(a).crossProduct(c.subtract(a)).length() * 0.5;
    }

    private static List<Vec3d> sortCap(List<Vec3d> pts, Vec3d desired) {
        Vec3d c = Vec3d.ZERO;
        for (Vec3d p : pts) c = c.add(p);
        c = c.multiply(1.0 / pts.size());
        Vec3d ref = Math.abs(desired.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        Vec3d u = desired.crossProduct(ref).normalize();
        Vec3d v = desired.crossProduct(u).normalize();
        final Vec3d cc = c, uu = u, vv = v;
        List<Vec3d> out = new ArrayList<>(pts);
        out.sort(Comparator.comparingDouble(p -> Math.atan2(
                p.subtract(cc).dotProduct(vv), p.subtract(cc).dotProduct(uu))));
        Vec3d n = faceNormal(out);
        if (n.dotProduct(desired) < 0) Collections.reverse(out);
        return out;
    }

    private static List<Vec3d> removeCollinear(List<Vec3d> input) {
        List<Vec3d> out = new ArrayList<>(input);
        boolean changed = true;
        while (changed && out.size() >= 3) {
            changed = false;
            for (int i = 0; i < out.size(); i++) {
                Vec3d a = out.get((i + out.size() - 1) % out.size());
                Vec3d b = out.get(i);
                Vec3d c = out.get((i + 1) % out.size());
                if (b.subtract(a).crossProduct(c.subtract(b)).lengthSquared() < 1.0E-18) {
                    out.remove(i);
                    changed = true;
                    break;
                }
            }
        }
        return out;
    }

    private static List<Vec3d> unique(List<Vec3d> in) {
        List<Vec3d> out = new ArrayList<>();
        outer: for (Vec3d p : in) {
            for (Vec3d q : out) if (p.squaredDistanceTo(q) < 1.0E-12) continue outer;
            out.add(p);
        }
        return out;
    }
}
