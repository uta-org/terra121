package io.github.terra121.events;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer;
import net.minecraftforge.fml.common.eventhandler.Event;

public class CubeHeightmapEvent extends Event {
    private CubePos cubePos;
    private double[][] heightmaps;
    private boolean surface;
    private CubePrimer primer;

    public CubeHeightmapEvent() {
        super();
    }

    public CubeHeightmapEvent(CubePos cubePos, CubePrimer primer, double[][] heightmaps, boolean surface) {
        this();
        this.cubePos = cubePos;
        this.primer = primer;
        this.heightmaps = heightmaps;
        this.surface = surface;
    }

    public CubePos getCubePos() {
        return cubePos;
    }

    public CubePrimer getPrimer() {
        return primer;
    }

    public double[][] getHeightmaps() {
        return heightmaps;
    }

    public boolean isSurface() {
        return surface;
    }
}
