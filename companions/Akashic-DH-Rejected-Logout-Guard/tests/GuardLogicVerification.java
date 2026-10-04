import com.akashic.compat.dhrejectedlogout.RejectedLogoutGuardHooks;

public final class GuardLogicVerification {
    public static void main(String[] args) {
        expect(true, RejectedLogoutGuardHooks.shouldSkip(null), "null event");

        DummyEvent nullPlayer = new DummyEvent(null);
        expect(true, RejectedLogoutGuardHooks.shouldSkip(nullPlayer), "null player");

        PlayerSrg noHandler = new PlayerSrg(null);
        expect(true, RejectedLogoutGuardHooks.shouldSkip(new DummyEvent(noHandler)), "null SRG handler");

        HandlerSrg noHandlerPlayer = new HandlerSrg(null);
        PlayerSrg missingHandlerPlayer = new PlayerSrg(noHandlerPlayer);
        expect(true, RejectedLogoutGuardHooks.shouldSkip(new DummyEvent(missingHandlerPlayer)),
            "null handler player");

        PlayerSrg validPlayer = new PlayerSrg(null);
        HandlerSrg validHandler = new HandlerSrg(validPlayer);
        validPlayer.field_71135_a = validHandler;
        expect(false, RejectedLogoutGuardHooks.shouldSkip(new DummyEvent(validPlayer)),
            "valid SRG lifecycle");
        PlayerMcp validMcpPlayer = new PlayerMcp(null);
        HandlerMcp validMcpHandler = new HandlerMcp(validMcpPlayer);
        validMcpPlayer.playerNetServerHandler = validMcpHandler;
        expect(false, RejectedLogoutGuardHooks.shouldSkip(new DummyEvent(validMcpPlayer)),
            "valid MCP lifecycle");

        expect(false, RejectedLogoutGuardHooks.shouldSkip(new DummyEvent(new UnknownPlayer())),
            "unknown shape must fail open");

        System.out.println("GuardLogicVerification: PASS");
    }

    private static void expect(boolean expected, boolean actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }

    public static final class DummyEvent {
        public Object player;
        DummyEvent(Object player) { this.player = player; }
    }
    public static final class PlayerSrg {
        public Object field_71135_a;
        PlayerSrg(Object handler) { this.field_71135_a = handler; }
    }

    public static final class HandlerSrg {
        public Object field_147369_b;
        HandlerSrg(Object player) { this.field_147369_b = player; }
    }

    public static final class PlayerMcp {
        public Object playerNetServerHandler;
        PlayerMcp(Object handler) { this.playerNetServerHandler = handler; }
    }

    public static final class HandlerMcp {
        public Object playerEntity;
        HandlerMcp(Object player) { this.playerEntity = player; }
    }

    public static final class UnknownPlayer {
    }
}
