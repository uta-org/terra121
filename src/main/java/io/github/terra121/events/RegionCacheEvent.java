package io.github.terra121.events;

import io.github.terra121.dataset.Region;
import net.minecraftforge.fml.common.eventhandler.Event;

public class RegionCacheEvent extends Event {
    public enum FailureType { FAILED, MAX_ATTEMPTS_EXCEEDED }
    private FailureType failureType;
    private boolean error;
    private Region region;

    private RegionCacheEvent() {}

    public RegionCacheEvent(Region region) {
        this.region = region;
    }

    public RegionCacheEvent(Region region, FailureType failureType) {
        this(region);
        this.error = true;
        this.failureType = failureType;
    }

    public Region getRegion() {
        return region;
    }

    public FailureType getFailureType() {
        return failureType;
    }

    public boolean isError() {
        return error;
    }

    public static class Pre extends RegionCacheEvent {

    }

    public static class Post extends RegionCacheEvent {
        private Post() {}

        public Post(Region region) {
            super(region);
        }

        public Post(Region region, FailureType failureType) {
            super(region, failureType);
        }
    }
}
