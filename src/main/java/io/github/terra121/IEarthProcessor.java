package io.github.terra121;

import io.github.terra121.projection.GeographicProjection;

public interface IEarthProcessor {
    GeographicProjection getProjection();
    EarthTerrainProcessor getProcessor();
}
