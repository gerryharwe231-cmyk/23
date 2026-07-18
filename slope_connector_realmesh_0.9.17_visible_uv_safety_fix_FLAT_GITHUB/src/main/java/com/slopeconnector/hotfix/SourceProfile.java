package com.slopeconnector.hotfix;

import net.minecraft.block.BlockState;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Source geometry in the moving path frame: S=tangent, W=width, N=normal. */
public final class SourceProfile {
    public static final byte MATERIAL_GENERAL = 0;
    public static final byte MATERIAL_RAIL = 1;
    public static final byte MATERIAL_POST = 2;
    public static final byte MATERIAL_PANE = 3;
    public static final byte MATERIAL_WALL = 4;
    public static final byte MATERIAL_FULL = 5;
    /** The renderer bends the original baked model and uses its original UVs. */
    public static final byte MATERIAL_BAKED_MODEL = 6;
    /** Collision-only prism; the renderer must not draw it. */
    public static final byte MATERIAL_COLLISION = 7;

    public static final byte MODEL_NONE = 0;
    /** Keep the source state and source-world orientation. Used by full blocks. */
    public static final byte MODEL_EXACT = 1;
    /** Use a canonical east-west connected state. Used by railings/fences/panes/walls. */
    public static final byte MODEL_CONNECTED = 2;

    record Part(double minS, double maxS,
                double minW, double maxW,
                double minN, double maxN,
                boolean continuous,
                byte materialHint) {}

    final List<Part> parts;
    final boolean fullLike;
    final double connectionReach;
    final byte modelMode;

    private SourceProfile(List<Part> parts, boolean fullLike,
                          double connectionReach, byte modelMode) {
        this.parts = List.copyOf(parts);
        this.fullLike = fullLike;
        this.connectionReach = connectionReach;
        this.modelMode = modelMode;
    }

    boolean usesBakedModel() { return modelMode != MODEL_NONE; }

    static SourceProfile from(World world, BlockPos pos, BlockState state,
                              Vec3d pathDirection, Vec3d widthAxis, Vec3d normalAxis) {
        if (state.isFullCube(world, pos)) {
            // A real baked cube gives exact source UVs and removes the blurred affine remapping.
            return new SourceProfile(List.of(), true, 0.5, MODEL_EXACT);
        }

        if (isConnectedModelCandidate(state)) {
            // Visuals come from the exact connected baked model. The outline is retained only for
            // collision, so a railing remains a railing rather than a solid one-block wall.
            BlockState connected = canonicalConnectedState(state);
            List<Part> collision = canonicalCollisionParts(world, pos, connected);
            return new SourceProfile(collision, false, 0.5, MODEL_CONNECTED);
        }

        // Non-connected custom shapes keep the older outline sweep as a safe fallback.
        return outlineProfile(world, pos, state, pathDirection, widthAxis, normalAxis,
                MATERIAL_GENERAL, MATERIAL_GENERAL, 0.5);
    }

    /** Client and server use the same canonical state selection. */
    public static BlockState canonicalModelState(BlockState original, byte mode) {
        return mode == MODEL_CONNECTED ? canonicalConnectedState(original) : original;
    }

    /**
     * Turns a real endpoint block into the one-sided state that points toward the first/last arc
     * cell. The endpoint itself stays a normal world block, so it can still be broken separately.
     */
    public static BlockState endpointConnectedState(BlockState original, Direction towardArc) {
        if (towardArc == null || towardArc.getAxis().isVertical()) return original;
        String target = towardArc.getName().toLowerCase(Locale.ROOT);
        for (Property<?> property : original.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            String value = switch (name) {
                case "north", "south", "east", "west" -> name.equals(target) ? "true" : "false";
                case "north_wall_shape", "south_wall_shape", "east_wall_shape", "west_wall_shape" ->
                        name.startsWith(target) ? "low" : "none";
                case "axis", "horizontal_axis" ->
                        towardArc.getAxis() == Direction.Axis.X ? "x" : "z";
                case "horizontal_facing", "facing" -> target;
                case "up" -> original.getBlock() instanceof WallBlock ? "true" : null;
                default -> null;
            };
            if (value != null) original = setSerializedValue(original, property, value);
        }
        return original;
    }

    private static boolean isConnectedModelCandidate(BlockState state) {
        if (state.getBlock() instanceof FenceBlock
                || state.getBlock() instanceof PaneBlock
                || state.getBlock() instanceof WallBlock) return true;

        int horizontalConnections = 0;
        boolean axis = false;
        boolean facing = false;
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            if (name.equals("north") || name.equals("south")
                    || name.equals("east") || name.equals("west")) horizontalConnections++;
            if (name.equals("axis") || name.equals("horizontal_axis")) axis = true;
            if (name.equals("horizontal_facing")) facing = true;
        }

        Identifier id = Registries.BLOCK.getId(state.getBlock());
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        boolean nameHint = path.contains("railing") || path.contains("balustrade")
                || path.contains("fence") || path.contains("iron_bars")
                || path.contains("bars_pane") || path.endsWith("_pane")
                || path.contains("parapet");

        // Four-way connection properties are strong evidence on their own. Axis/facing alone is
        // not: logs, pillars and stairs also expose those properties and must remain normal models.
        return horizontalConnections >= 2 || (nameHint && (axis || facing)) || nameHint;
    }

    private static List<Part> canonicalCollisionParts(World world, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getOutlineShape(world, pos);
        List<Box> boxes = shape.isEmpty()
                ? List.of(new Box(0, 0, 0, 1, 1, 1))
                : shape.getBoundingBoxes();
        List<Part> parts = new ArrayList<>();
        for (Box box : boxes) {
            double minS = box.minX - 0.5, maxS = box.maxX - 0.5;
            double minW = box.minZ - 0.5, maxW = box.maxZ - 0.5;
            double minN = box.minY - 0.5, maxN = box.maxY - 0.5;
            if (maxS - minS < 1.0E-4 || maxW - minW < 1.0E-4 || maxN - minN < 1.0E-4) continue;
            boolean continuous = maxS - minS >= 0.72;
            parts.add(new Part(minS, maxS, minW, maxW, minN, maxN,
                    continuous, MATERIAL_COLLISION));
        }
        if (parts.isEmpty()) {
            parts.add(new Part(-0.5, 0.5, -0.0625, 0.0625, -0.5, 0.5,
                    true, MATERIAL_COLLISION));
        }
        return parts;
    }

    private static SourceProfile outlineProfile(World world, BlockPos pos, BlockState state,
                                                 Vec3d pathDirection, Vec3d widthAxis, Vec3d normalAxis,
                                                 byte continuousHint, byte repeatedHint,
                                                 double defaultReach) {
        VoxelShape shape = state.getOutlineShape(world, pos);
        List<Box> boxes = shape.isEmpty()
                ? List.of(new Box(0, 0, 0, 1, 1, 1))
                : shape.getBoundingBoxes();
        List<Part> parts = new ArrayList<>();
        double coreReach = 0.0;
        for (Box box : boxes) {
            double minS = Double.POSITIVE_INFINITY, maxS = Double.NEGATIVE_INFINITY;
            double minW = Double.POSITIVE_INFINITY, maxW = Double.NEGATIVE_INFINITY;
            double minN = Double.POSITIVE_INFINITY, maxN = Double.NEGATIVE_INFINITY;
            for (int ix = 0; ix < 2; ix++) for (int iy = 0; iy < 2; iy++) for (int iz = 0; iz < 2; iz++) {
                Vec3d local = new Vec3d(
                        (ix == 0 ? box.minX : box.maxX) - 0.5,
                        (iy == 0 ? box.minY : box.maxY) - 0.5,
                        (iz == 0 ? box.minZ : box.maxZ) - 0.5);
                double s = local.dotProduct(pathDirection);
                double w = local.dotProduct(widthAxis);
                double n = local.dotProduct(normalAxis);
                minS = Math.min(minS, s); maxS = Math.max(maxS, s);
                minW = Math.min(minW, w); maxW = Math.max(maxW, w);
                minN = Math.min(minN, n); maxN = Math.max(maxN, n);
            }
            if (maxS - minS < 1.0E-4 || maxW - minW < 1.0E-4 || maxN - minN < 1.0E-4) continue;
            boolean continuous = maxS - minS >= 0.72;
            byte hint = continuous ? continuousHint : repeatedHint;
            parts.add(new Part(minS, maxS, minW, maxW, minN, maxN, continuous, hint));
            if (!continuous && minS <= 1.0E-5 && maxS >= -1.0E-5) {
                coreReach = Math.max(coreReach, Math.max(Math.abs(minS), Math.abs(maxS)));
            }
        }
        if (parts.isEmpty()) {
            parts.add(new Part(-0.5, 0.5, -0.5, 0.5, -0.5, 0.5,
                    true, continuousHint));
            return new SourceProfile(parts, false, 0.5, MODEL_NONE);
        }
        double reach = coreReach > 1.0E-4 ? coreReach : defaultReach;
        return new SourceProfile(parts, false,
                Math.max(0.03125, Math.min(0.5, reach)), MODEL_NONE);
    }

    private static BlockState canonicalConnectedState(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            String value = switch (name) {
                case "east", "west" -> "true";
                case "north", "south" -> "false";
                case "axis", "horizontal_axis" -> "x";
                case "horizontal_facing", "facing" -> "east";
                case "east_wall_shape", "west_wall_shape" -> "low";
                case "north_wall_shape", "south_wall_shape" -> "none";
                case "up" -> state.getBlock() instanceof WallBlock ? "true" : null;
                default -> null;
            };
            if (value != null) state = setSerializedValue(state, property, value);
        }
        return state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState setSerializedValue(BlockState state, Property property, String wanted) {
        Collection<? extends Comparable> values = property.getValues();
        for (Comparable value : values) {
            String serialized;
            try {
                serialized = property.name(value);
            } catch (RuntimeException ignored) {
                serialized = value.toString();
            }
            if (wanted.equalsIgnoreCase(serialized) || wanted.equalsIgnoreCase(value.toString())) {
                try {
                    return state.with(property, value);
                } catch (IllegalArgumentException ignored) {
                    return state;
                }
            }
        }
        return state;
    }
}
