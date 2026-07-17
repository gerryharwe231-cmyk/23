package com.slopeconnector.hotfix;

import java.util.ArrayList;
import java.util.List;

final class ArcPath {
    private static final double EPS = 1.0E-7;
    private static final double SAMPLES_PER_BLOCK = 2.25;

    record Sample(double s, double o, double ns, double no, double distance) {}
    record Result(List<Sample> samples, String error) {
        boolean valid() { return error == null || error.isEmpty(); }
        static Result error(String error) { return new Result(List.of(), error); }
    }

    private ArcPath() {}

    static Result twoPoint(double run, double delta, int side) {
        if (run < 0.05) return Result.error("两个端点在连接平面内距离太短");
        if (Math.abs(delta) < 0.05) return sameLevel(run, side < 0 ? -1 : 1);

        double sign = Math.signum(delta);
        double rise = Math.abs(delta);
        double radius = Math.min(run, rise);
        if (radius < 0.08) return Result.error("高度差或距离太小，无法形成圆弧");

        double straightA = run - radius;
        double straightB = rise - radius;
        List<Sample> raw = new ArrayList<>();
        appendLine(raw, 0.0, 0.0, straightA, 0.0, 0.0, 1.0, segmentCount(straightA));

        double cx = straightA;
        double co = sign * radius;
        double a0 = -sign * Math.PI * 0.5;
        double a1 = 0.0;
        int arcSteps = Math.max(8, (int)Math.ceil(radius * Math.PI * 0.5 * SAMPLES_PER_BLOCK));
        for (int i = 0; i <= arcSteps; i++) {
            if (!raw.isEmpty() && i == 0) continue;
            double t = i / (double)arcSteps;
            double a = a0 + (a1 - a0) * t;
            double s = cx + radius * Math.cos(a);
            double o = co + radius * Math.sin(a);
            double ts = -radius * Math.sin(a) * (a1 - a0);
            double to = radius * Math.cos(a) * (a1 - a0);
            addWithNormal(raw, s, o, ts, to);
        }
        appendLine(raw, run, sign * radius, run, delta, 0.0, sign, segmentCount(straightB));
        return finish(raw);
    }

    private static Result sameLevel(double run, int side) {
        double sag = Math.max(0.35, Math.min(run * 0.28, run * 0.5 - 0.02)) * side;
        return throughThree(0.0, 0.0, run * 0.5, sag, run, 0.0);
    }

    /**
     * Three-point mode is a waypoint path, not a control-point circle.
     * The returned path always visits P0 -> P1 -> P2 in that order. Two cubic Hermite segments
     * share one automatically selected tangent at P1, so there is no reversal or one-sided kink.
     */
    static Result threePoint(double run, double delta, double cs, double co) {
        if (run < 0.05) return Result.error("两个端点在连接平面内距离太短");
        double x0 = 0.0, y0 = 0.0;
        double x1 = cs, y1 = co;
        double x2 = run, y2 = delta;
        double l01 = Math.hypot(x1 - x0, y1 - y0);
        double l12 = Math.hypot(x2 - x1, y2 - y1);
        if (l01 < 0.05 || l12 < 0.05) return Result.error("第二个定位点不能与起点或终点重合");

        double t0x = (x1 - x0) / l01;
        double t0y = (y1 - y0) / l01;
        double t2x = (x2 - x1) / l12;
        double t2y = (y2 - y1) / l12;
        double midX = t0x + t2x;
        double midY = t0y + t2y;
        double midLen = Math.hypot(midX, midY);
        if (midLen < 1.0E-5) {
            // A near U-turn cannot have a unique smooth tangent. Choose the shorter, non-reversing
            // direction by falling back to the direct first-to-third direction.
            midX = x2 - x0;
            midY = y2 - y0;
            midLen = Math.hypot(midX, midY);
            if (midLen < 1.0E-5) return Result.error("三点路径方向无法确定");
        }
        midX /= midLen;
        midY /= midLen;

        List<Sample> raw = new ArrayList<>();
        int stepsA = Math.max(6, (int)Math.ceil(l01 * SAMPLES_PER_BLOCK));
        int stepsB = Math.max(6, (int)Math.ceil(l12 * SAMPLES_PER_BLOCK));
        appendHermite(raw, x0, y0, x1, y1,
                t0x * l01 * 0.75, t0y * l01 * 0.75,
                midX * Math.min(l01, l12) * 0.75, midY * Math.min(l01, l12) * 0.75,
                stepsA, false);
        appendHermite(raw, x1, y1, x2, y2,
                midX * Math.min(l01, l12) * 0.75, midY * Math.min(l01, l12) * 0.75,
                t2x * l12 * 0.75, t2y * l12 * 0.75,
                stepsB, true);
        return finish(raw);
    }

    private static Result throughThree(double x1, double y1, double x2, double y2, double x3, double y3) {
        double d = 2.0 * (x1 * (y2 - y3) + x2 * (y3 - y1) + x3 * (y1 - y2));
        if (Math.abs(d) < EPS) {
            List<Sample> line = new ArrayList<>();
            appendLine(line, x1, y1, x3, y3, x3 - x1, y3 - y1, segmentCount(Math.hypot(x3-x1, y3-y1)));
            return finish(line);
        }
        double q1 = x1*x1 + y1*y1;
        double q2 = x2*x2 + y2*y2;
        double q3 = x3*x3 + y3*y3;
        double cx = (q1*(y2-y3) + q2*(y3-y1) + q3*(y1-y2)) / d;
        double cy = (q1*(x3-x2) + q2*(x1-x3) + q3*(x2-x1)) / d;
        double r = Math.hypot(x1-cx, y1-cy);
        if (!Double.isFinite(r) || r < 0.05 || r > 4096.0) return Result.error("圆弧半径无效");
        double a1 = Math.atan2(y1-cy, x1-cx);
        double a2 = Math.atan2(y2-cy, x2-cx);
        double a3 = Math.atan2(y3-cy, x3-cx);
        double ccw13 = positive(a3-a1);
        double ccw12 = positive(a2-a1);
        double sweep = ccw12 <= ccw13 + 1.0E-5 ? ccw13 : -(Math.PI*2.0-ccw13);
        int steps = Math.max(12, Math.min(2048, (int)Math.ceil(Math.abs(sweep) * r * SAMPLES_PER_BLOCK)));
        List<Sample> raw = new ArrayList<>();
        for (int i=0;i<=steps;i++) {
            double t=i/(double)steps;
            double a=a1+sweep*t;
            double ts=-r*Math.sin(a)*sweep;
            double to= r*Math.cos(a)*sweep;
            addWithNormal(raw, cx+r*Math.cos(a), cy+r*Math.sin(a), ts, to);
        }
        return finish(raw);
    }

    private static void appendHermite(List<Sample> out,
                                      double x0, double y0, double x1, double y1,
                                      double m0x, double m0y, double m1x, double m1y,
                                      int steps, boolean skipFirst) {
        for (int i = 0; i <= steps; i++) {
            if (skipFirst && i == 0) continue;
            double t = i / (double)steps;
            double t2 = t*t, t3 = t2*t;
            double h00 = 2*t3 - 3*t2 + 1;
            double h10 = t3 - 2*t2 + t;
            double h01 = -2*t3 + 3*t2;
            double h11 = t3 - t2;
            double x = h00*x0 + h10*m0x + h01*x1 + h11*m1x;
            double y = h00*y0 + h10*m0y + h01*y1 + h11*m1y;
            double dh00 = 6*t2 - 6*t;
            double dh10 = 3*t2 - 4*t + 1;
            double dh01 = -6*t2 + 6*t;
            double dh11 = 3*t2 - 2*t;
            double tx = dh00*x0 + dh10*m0x + dh01*x1 + dh11*m1x;
            double ty = dh00*y0 + dh10*m0y + dh01*y1 + dh11*m1y;
            addWithNormal(out, x, y, tx, ty);
        }
    }

    private static void appendLine(List<Sample> out, double s0, double o0, double s1, double o1,
                                   double ts, double to, int steps) {
        if (Math.hypot(s1-s0, o1-o0) < EPS) return;
        steps = Math.max(1, steps);
        for (int i=0;i<=steps;i++) {
            if (!out.isEmpty() && i==0) continue;
            double t=i/(double)steps;
            addWithNormal(out, s0+(s1-s0)*t, o0+(o1-o0)*t, ts, to);
        }
    }

    private static void addWithNormal(List<Sample> out, double s, double o, double ts, double to) {
        double len = Math.hypot(ts, to);
        if (len < EPS) return;
        double ns = -to / len;
        double no = ts / len;
        out.add(new Sample(s, o, ns, no, 0.0));
    }

    private static Result finish(List<Sample> input) {
        List<Sample> out = new ArrayList<>();
        double distance = 0.0;
        for (Sample p : input) {
            if (out.isEmpty()) {
                out.add(new Sample(p.s, p.o, p.ns, p.no, 0.0));
                continue;
            }
            Sample q = out.get(out.size()-1);
            double ds = Math.hypot(p.s-q.s, p.o-q.o);
            if (ds < 1.0E-6) continue;
            distance += ds;
            out.add(new Sample(p.s, p.o, p.ns, p.no, distance));
        }
        return out.size() < 2 ? Result.error("路径采样失败") : new Result(out, "");
    }

    private static int segmentCount(double length) {
        return Math.max(1, (int)Math.ceil(Math.abs(length) * SAMPLES_PER_BLOCK));
    }

    private static double positive(double a) {
        double two=Math.PI*2.0;
        a%=two;
        if(a<0)a+=two;
        return a;
    }
}
