package io.github.terra121.events;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import net.minecraftforge.fml.common.eventhandler.Event;

public class CubeHeightmapEvent extends Event {
    private CubePos cubePos;
    private double[][] heightmaps;
    private boolean surface;

    public CubeHeightmapEvent() {
        super();
    }

    public CubeHeightmapEvent(CubePos cubePos, double[][] heightmaps, boolean surface) {
        this();
        this.cubePos = cubePos;
        this.heightmaps = heightmaps;
        this.surface = surface;
    }

    public CubePos getCubePos() {
        return cubePos;
    }

    public double[][] getHeightmaps() {
        return heightmaps;
    }

    public boolean isSurface() {
        return surface;
    }
}
