package dev.xcrafttm.ddbeam.client.cast;

import com.dreamdisplays.api.Display;
import com.dreamdisplays.api.DreamDisplays;
import com.dreamdisplays.api.display.model.DisplayInfo;
import com.dreamdisplays.api.display.model.property.DisplayBounds;
import com.dreamdisplays.api.display.model.property.DisplayFacing;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The Dream Displays displays this client knows, and which one the player looks at. */
public final class Targets {
    private Targets() {
    }

    /**
     * A display's block box (one block deep) and the center of its picture.
     *
     * @param width  picture width in blocks
     * @param height picture height in blocks
     */
    public record Target(UUID id, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                         int width, int height, DisplayFacing facing) {
        public Vec3 center() {
            return new Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
        }

        /** Picture size in pixels for this display's shape, at most {@code maxW} x {@code maxH} (even sides). */
        public int[] pictureSize(int maxW, int maxH) {
            double aspect = (double) Math.max(1, width) / Math.max(1, height);
            int w = maxW, h = (int) Math.round(maxW / aspect);
            if (h > maxH) {
                h = maxH;
                w = (int) Math.round(maxH * aspect);
            }
            return new int[]{Math.max(16, w & ~1), Math.max(16, h & ~1)};
        }
    }

    public static List<Target> all() {
        List<Target> out = new ArrayList<>();
        try {
            for (Display display : DreamDisplays.getDisplays()) {
                Target target = of(display);
                if (target != null) out.add(target);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Dream Displays not ready (no world yet)
        }
        return out;
    }

    public static Target byId(UUID id) {
        for (Target target : all()) if (target.id().equals(id)) return target;
        return null;
    }

    private static Target of(Display display) {
        DisplayInfo info = display.getInfo();
        if (info == null) return null;
        DisplayBounds b = info.getBounds();
        int w = b.getWidth(), h = b.getHeight();
        double x = Math.floor(b.getX()), y = Math.floor(b.getY()), z = Math.floor(b.getZ());
        // the box, as Dream Displays' DisplayGeometry builds it (one block deep)
        double maxX = x + 1, maxY = y + h, maxZ = z + 1;
        switch (b.getFacing()) {
            case NORTH, SOUTH -> maxX = x + w;
            case EAST, WEST -> maxZ = z + w;
            case UP, DOWN -> {
                maxX = x + w;
                maxZ = z + h;
                maxY = y + 1;
            }
        }
        return new Target(display.getId().getUuid(), x, y, z, maxX, maxY, maxZ, w, h, b.getFacing());
    }

    /** The display under the crosshair within {@code reach} blocks, else the nearest one within 8 blocks. */
    public static Target lookedAt(double reach) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 dir = mc.player.getViewVector(1f);
        Target best = null, nearest = null;
        double bestT = reach, nearestD = 8;
        for (Target t : all()) {
            double hit = intersect(eye, dir, t, 0.1);
            if (hit >= 0 && hit < bestT) {
                bestT = hit;
                best = t;
            }
            double d = t.center().distanceTo(eye);
            if (d < nearestD) {
                nearestD = d;
                nearest = t;
            }
        }
        return best != null ? best : nearest;
    }

    /** Ray / box slab test; the distance along the ray, or -1. */
    private static double intersect(Vec3 o, Vec3 d, Target t, double grow) {
        double tMin = 0, tMax = Double.MAX_VALUE;
        double[] origin = {o.x, o.y, o.z}, dir = {d.x, d.y, d.z};
        double[] min = {t.minX() - grow, t.minY() - grow, t.minZ() - grow}, max = {t.maxX() + grow, t.maxY() + grow, t.maxZ() + grow};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(dir[i]) < 1e-9) {
                if (origin[i] < min[i] || origin[i] > max[i]) return -1;
                continue;
            }
            double a = (min[i] - origin[i]) / dir[i], b = (max[i] - origin[i]) / dir[i];
            tMin = Math.max(tMin, Math.min(a, b));
            tMax = Math.min(tMax, Math.max(a, b));
            if (tMin > tMax) return -1;
        }
        return tMin;
    }
}
