import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

public final class ModConstructionVerification {
    public static void main(String[] args) throws Exception {
        Class<?> modClass = Class.forName(
            "com.akashic.compat.dhendlessids.AkashicDHEndlessIDsCompat"
        );
        Constructor<?> constructor = modClass.getDeclaredConstructor();
        if (!Modifier.isPublic(constructor.getModifiers())) {
            throw new AssertionError("FML @Mod no-arg constructor must be public");
        }
        Object instance = constructor.newInstance();
        if (instance == null) {
            throw new AssertionError("FML @Mod instance was null");
        }
        System.out.println("MOD_CONSTRUCTION_OK");
    }
}
