#!/usr/bin/env python3

# Mirrors ArcMaterialHelper.affine and protects the constant-term sign.
def affine(s, t, q):
    for i in range(4):
        for j in range(i + 1, 4):
            for k in range(j + 1, 4):
                det = s[i] * (t[j] - t[k]) + s[j] * (t[k] - t[i]) + s[k] * (t[i] - t[j])
                if abs(det) < 1.0e-6:
                    continue
                a = (q[i] * (t[j] - t[k]) + q[j] * (t[k] - t[i]) + q[k] * (t[i] - t[j])) / det
                b = (s[i] * (q[j] - q[k]) + s[j] * (q[k] - q[i]) + s[k] * (q[i] - q[j])) / det
                c = (q[i] * (s[j] * t[k] - s[k] * t[j])
                     + q[j] * (s[k] * t[i] - s[i] * t[k])
                     + q[k] * (s[i] * t[j] - s[j] * t[i])) / det
                return a, b, c
    raise AssertionError("singular input")

def check(q, expected):
    s = [0.0, 1.0, 1.0, 0.0]
    t = [0.0, 0.0, 1.0, 1.0]
    result = affine(s, t, q)
    for got, want in zip(result, expected):
        assert abs(got - want) < 1.0e-6, (result, expected)
    for index in range(4):
        reconstructed = result[0] * s[index] + result[1] * t[index] + result[2]
        assert abs(reconstructed - q[index]) < 1.0e-6

check([0.20, 0.80, 0.80, 0.20], (0.60, 0.00, 0.20))
check([0.30, 0.30, 0.90, 0.90], (0.00, 0.60, 0.30))
check([0.42, 0.42, 0.42, 0.42], (0.00, 0.00, 0.42))
print("UV affine regression test passed")
