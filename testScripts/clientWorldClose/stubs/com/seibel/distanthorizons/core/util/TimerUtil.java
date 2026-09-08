package com.seibel.distanthorizons.core.util;
import java.util.Timer;
/** Tracks cancellation while retaining the JDK Timer's real scheduling/cancel behavior. */
public final class TimerUtil
{
    public static TrackingTimer lastTimer;
    public static Timer CreateTimer(String name) { return lastTimer = new TrackingTimer(name); }
    public static final class TrackingTimer extends Timer
    {
        public int cancelCalls;
        TrackingTimer(String name) { super(name, true); }
        @Override public void cancel() { this.cancelCalls++; super.cancel(); }
    }
}
