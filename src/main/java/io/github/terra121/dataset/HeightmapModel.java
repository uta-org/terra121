package io.github.terra121.dataset;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;

import java.util.HashMap;
import java.util.Map;

public class HeightmapModel {
    private boolean surface;
    private double[][] heightmap;
    // private int minY;

    public double[][] getHeightmap() {
        return heightmap;
    }

    public void setHeightmap(double[][] heightmap) {
        this.heightmap = heightmap;
    }

    /* // z3nth10n: TODO, this can be a nice feature to have (+ maxY, meanY, in order to make this quicker for other mods)
    public int getMinY() {
        return minY;
    }

    public void setMinY(int minY) {
        this.minY = minY;
    }
     */

    public boolean getSurface() {
       return surface;
    }

    public void setSurface(boolean surface) {
        this.surface = surface;
    }

    private static final Map<CubePos, HeightmapModel> cachedHeightmaps = new HashMap<>();

    public static HeightmapModel getModel(int chunkX, int chunkY, int chunkZ) {
        return getModel(new CubePos(chunkX, chunkY, chunkZ), true);
    }

    public static HeightmapModel getModel(CubePos pos) {
        return getModel(pos, true);
    }

    public static HeightmapModel getModel(CubePos pos, boolean remove) {
        if(!cachedHeightmaps.containsKey(pos))
            return null;

        // Once check remove if flag checked
        if(remove) cachedHeightmaps.remove(pos);
        return cachedHeightmaps.get(pos);
    }

    public static void add(CubePos pos, HeightmapModel model) {
        cachedHeightmaps.put(pos, model);
    }
}