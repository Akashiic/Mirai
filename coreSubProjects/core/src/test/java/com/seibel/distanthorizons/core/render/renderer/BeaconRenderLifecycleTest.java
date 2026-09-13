package com.seibel.distanthorizons.core.render.renderer;

import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderObjectFactory;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhGenericRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BeaconRenderLifecycleTest
{
    @Test public void closingOwnerSkipsCameraAndLeavesRenderGroupUntouched()
    {
        Fixture fixture = new Fixture();
        fixture.closing.set(true);
        fixture.handler.updateBeaconCullingOnce();
        fixture.assertSkipped(0);
    }
    @Test public void missingCameraSnapshotSkipsWithoutUnsafeFallback()
    {
        Fixture fixture = new Fixture();
        fixture.camera = null;
        fixture.handler.updateBeaconCullingOnce();
        fixture.assertSkipped(1);
    }

    @Test public void missingLevelWrapperSkipsWithoutUnsafeFallback()
    {
        Fixture fixture = new Fixture();
        fixture.levelAvailable = false;
        fixture.handler.updateBeaconCullingOnce();
        fixture.assertSkipped(0);
    }

    @Test public void ownerClosingAfterCameraSnapshotCannotPublishStaleCulling()
    {
        Fixture fixture = new Fixture();
        fixture.camera = new DhVec3d(1, 2, 3);
        fixture.closeOnCameraQuery = true;
        fixture.handler.updateBeaconCullingOnce();
        fixture.assertSkipped(1);
        assertTrue(fixture.closing.get());
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler)
    { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler)); }
    private static Object defaultReturn(Object proxy, Method method)
    {
        if (method.getReturnType().isInstance(proxy)) { return proxy; }
        if (method.getReturnType() == boolean.class) { return false; }
        if (method.getReturnType() == int.class) { return 0; }
        if (method.getReturnType() == long.class) { return 0L; }
        if (method.getReturnType() == float.class) { return 0F; }
        if (method.getReturnType() == double.class) { return 0D; }
        return null;
    }

    private static final class Group implements InvocationHandler
    {
        final IDhApiRenderableBoxGroup api = proxy(IDhApiRenderableBoxGroup.class, this);
        int clears;

        @Override public Object invoke(Object proxy, Method method, Object[] args)
        {
            if ("clear".equals(method.getName())) { clears++; return null; }
            return defaultReturn(proxy, method);
        }
    }

    private static final class Fixture
    {
        final AtomicBoolean closing = new AtomicBoolean(false);
        final Group group = new Group();
        final IClientLevelWrapper level;
        final IMinecraftRenderWrapper renderWrapper;
        final BeaconRenderHandler handler;
        DhVec3d camera = new DhVec3d(0, 64, 0);
        boolean closeOnCameraQuery;
        boolean levelAvailable = true;
        int cameraQueries;
        int exactCameraQueries;

        Fixture()
        {
            this.level = proxy(IClientLevelWrapper.class,
                    (object, method, args) -> defaultReturn(object, method));
            this.renderWrapper = proxy(IMinecraftRenderWrapper.class, (object, method, args) ->
            {
                if ("getCameraPositionForLodTick".equals(method.getName()))
                {
                    cameraQueries++;
                    assertSame(level, args[0]);
                    if (closeOnCameraQuery) { closing.set(true); }
                    return camera;
                }
                if ("getCameraExactPosition".equals(method.getName()))
                { exactCameraQueries++; return camera; }
                return defaultReturn(object, method);
            });
            IDhGenericRenderer renderer = proxy(IDhGenericRenderer.class,
                    (object, method, args) -> defaultReturn(object, method));
            IDhApiCustomRenderObjectFactory factory = proxy(IDhApiCustomRenderObjectFactory.class,
                    (object, method, args) -> "createAbsolutePositionedGroup".equals(method.getName())
                            ? group.api : defaultReturn(object, method));
            this.handler = new BeaconRenderHandler(renderer,
                    () -> this.levelAvailable ? this.level : null,
                    this.closing::get, this.renderWrapper, factory);
        }

        void assertSkipped(int expectedCameraQueries)
        {
            assertEquals(expectedCameraQueries, this.cameraQueries);
            assertEquals(0, this.exactCameraQueries);
            assertEquals(0, this.group.clears);
        }
    }
}
