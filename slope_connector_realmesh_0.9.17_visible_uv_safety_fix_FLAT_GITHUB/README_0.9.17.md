# Slope Connector RealMesh 0.9.17 — Visible UV Safety Fix

This release fixes the complete invisibility introduced in 0.9.16.

## Root cause

The baked-quad UV affine solver used the wrong sign for the constant term `c` in `q = a*s + b*t + c`. The generated geometry was valid, but its UVs sampled outside the source sprite in the block atlas, so solid arc, trim, fence and pane meshes could all appear transparent/invisible.

## Fixes

- Correct Cramer-rule constant term for baked-model UV transforms.
- Validate the transform against all four source quad vertices.
- Fall back to the source sprite rectangle whenever a transform is non-finite or does not reproduce the baked quad.
- Clamp final U/V coordinates to the selected sprite bounds, preventing texture-atlas bleed or transparent sampling.
- Keeps the 0.9.16 endpoint, connected-profile, passive trim, collision and performance changes.

## Upgrade

Delete the 0.9.16 jar and use only the jar built from this project. Existing 0.9.16 arc block entities should become visible after replacing the jar and reloading the world because the stored geometry is still valid; newly generated arcs are recommended for verifying endpoint/trim behavior.
