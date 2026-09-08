package com.seibel.distanthorizons.common.render.openGl;

import com.seibel.distanthorizons.core.logging.mirai.MiraiGpuProfiler;
import com.seibel.distanthorizons.core.logging.mirai.MiraiDiagnostics;
import com.seibel.distanthorizons.lwjgl.EGLExtension;
import com.seibel.distanthorizons.lwjgl.ILWJGLService;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

/** Context-owned timestamp queries; the ring controls availability before reading results. */
public final class MiraiGlGpuTimerBackend implements MiraiGpuProfiler.ContextBackend
{
    private static final int GL_TIMESTAMP = 0x8E28;
    private static final int GL_QUERY_RESULT = 0x8866;
    private static final int GL_QUERY_RESULT_AVAILABLE = 0x8867;

    private final ILWJGLService service;
    private final Object contextToken;
    private final Thread owner;
    private final int counterBits;

    private MiraiGlGpuTimerBackend(ILWJGLService service, Object contextToken, int counterBits)
    {
        this.service = service;
        this.contextToken = contextToken;
        this.owner = Thread.currentThread();
        this.counterBits = counterBits;
    }

    public static MiraiGlGpuTimerBackend createIfSupported()
    {
        Object token = LWJGL.getCurrentContextToken();
        if (token == null)
        {
            recordSupport("no_context", "unknown", false);
            return null;
        }
        boolean core = LWJGL.isOpenGLVersionSupported(3, 3);
        if (!core && !LWJGL.isExtensionSupported(EGLExtension.ARB_timer_query))
        {
            recordSupport("none", "unknown", false);
            return null;
        }
        int bits = LWJGL.getTimestampCounterBits();
        recordSupport(core ? "OpenGL33" : "ARB_timer_query", Integer.toString(bits), bits == 64);
        // Smaller timestamp counters can wrap within a delayed sample. Do not guess elapsed time.
        if (bits != 64) { return null; }
        return new MiraiGlGpuTimerBackend(LWJGL, token, bits);
    }

    private static void recordSupport(String capability, String bits, boolean supported)
    {
        MiraiDiagnostics.metadata("gpu.timer_capability", capability);
        MiraiDiagnostics.metadata("gpu.timer_counter_bits", bits);
        MiraiDiagnostics.metadata("gpu.timer_supported", Boolean.toString(supported));
    }

    @Override
    public boolean isCurrentContext()
    {
        return Thread.currentThread() == this.owner
                && this.service.getCurrentContextToken() == this.contextToken;
    }

    @Override
    public int counterBits() { return this.counterBits; }

    private void requireCurrentContext()
    {
        if (!this.isCurrentContext())
        {
            throw new IllegalStateException("GPU timer query belongs to a different thread or GL context");
        }
    }

    @Override
    public int create()
    {
        this.requireCurrentContext();
        return this.service.glGenQueries();
    }

    @Override
    public void delete(int query)
    {
        this.requireCurrentContext();
        this.service.glDeleteQueries(query);
    }

    @Override
    public void timestamp(int query)
    {
        this.requireCurrentContext();
        this.service.glQueryCounter(query, GL_TIMESTAMP);
    }

    @Override
    public boolean available(int query)
    {
        this.requireCurrentContext();
        // The ring reads this packet immediately afterwards, with no intervening render callbacks.
        // Preserve another renderer's binding: skip collection rather than redirecting native getters.
        if (this.service.isQueryBufferBound()) { return false; }
        return this.service.glGetQueryObjectui64(query, GL_QUERY_RESULT_AVAILABLE) != 0L;
    }

    @Override
    public long result(int query)
    {
        this.requireCurrentContext();
        return this.service.glGetQueryObjectui64(query, GL_QUERY_RESULT);
    }
}
