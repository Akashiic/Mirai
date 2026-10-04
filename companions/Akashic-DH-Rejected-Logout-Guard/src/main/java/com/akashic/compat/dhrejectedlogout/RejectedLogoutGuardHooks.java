package com.akashic.compat.dhrejectedlogout;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

public final class RejectedLogoutGuardHooks {
    private static final Logger LOG = LogManager.getLogger("AkashicDHRejectedLogoutGuard");
    private static final Object FIELD_UNAVAILABLE = new Object();
    private static final AtomicLong SKIPPED = new AtomicLong();

    private RejectedLogoutGuardHooks() {
    }

    public static boolean shouldSkip(Object event) {
        if (event == null) {
            return skip("event=null");
        }

        Object player = readField(event, "player");
        if (player == FIELD_UNAVAILABLE) {
            return false;
        }
        if (player == null) {
            return skip("event.player=null");
        }

        Object handler = readField(player, "playerNetServerHandler", "field_71135_a");
        if (handler == FIELD_UNAVAILABLE) {
            return false;
        }
        if (handler == null) {
            return skip("EntityPlayerMP.NetHandlerPlayServer=null");
        }

        Object handlerPlayer = readField(handler, "playerEntity", "field_147369_b");
        if (handlerPlayer == FIELD_UNAVAILABLE) {
            return false;
        }
        if (handlerPlayer == null) {
            return skip("NetHandlerPlayServer.playerEntity=null");
        }

        return false;
    }

    private static boolean skip(String reason) {
        long count = SKIPPED.incrementAndGet();
        if (count <= 5L || count % 100L == 0L) {
            LOG.warn(
                "Suppressed incomplete Distant Horizons logout lifecycle event: {} (count={}).",
                reason,
                count
            );
        }
        return true;
    }

    private static Object readField(Object target, String... candidateNames) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : candidateNames) {
                try {
                    Field field = type.getDeclaredField(name);
                    if (!field.isAccessible()) {
                        field.setAccessible(true);
                    }
                    return field.get(target);
                } catch (NoSuchFieldException ignored) {
                    // Try the next SRG/MCP name or superclass.
                } catch (Throwable t) {
                    LOG.warn("Unable to inspect logout lifecycle field {} on {}. Failing open.",
                        name, type.getName(), t);
                    return FIELD_UNAVAILABLE;
                }
            }
            type = type.getSuperclass();
        }
        return FIELD_UNAVAILABLE;
    }

    static long skippedCountForTests() {
        return SKIPPED.get();
    }
}
