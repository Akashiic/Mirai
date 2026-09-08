package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.world.EWorldEnvironment;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataSourceProvider;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.AbstractDhServerLevel;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.level.ServerLevelModule;
import com.seibel.distanthorizons.core.multiplayer.config.SessionConfig;
import com.seibel.distanthorizons.core.network.messages.AbstractTrackableMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.network.session.NetworkSession;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Production readSync + real SQLite; Minecraft construction and the compression scheduling boundary are fixtures. */
public class CachedLodServerReadTest
{
    @Rule public final TemporaryFolder files = new TemporaryFolder();
    private static final long POS = DhSectionPos.encode((byte) 6, -3, 4);
    private static <T> T allocate(Class<T> type) throws Exception
    {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }
    private static void set(Object target, Class<?> type, String name, Object value) throws Exception
    { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); }

    private static final class World extends AbstractDhWorld
    {
        World() { super(EWorldEnvironment.SERVER_ONLY); }
        @Override public IDhLevel getOrLoadLevel(ILevelWrapper wrapper) { return wrapper.getDhLevel(); }
        @Override public IDhLevel getLevel(ILevelWrapper wrapper) { return wrapper.getDhLevel(); }
        @Override public Iterable<? extends IDhLevel> getAllLoadedLevels() { return Collections.emptyList(); }
        @Override public int getLoadedLevelCount() { return 1; }
        @Override public boolean unloadLevel(ILevelWrapper wrapper) { return false; }
        @Override public void close() { }
    }
    private static final class CachedOnlyConfig extends SessionConfig
    {
        @Override public boolean isDistantGenerationEnabled() { return false; }
        @Override public boolean getSynchronizeOnLoad() { return true; }
    }
    private static final class Message extends FullDataSourceRequestMessage
    {
        AbstractTrackableMessage response;
        Exception failure;
        Message(IServerLevelWrapper level) { super(level, POS, CACHE_ONLY_TIMESTAMP); }
        @Override public void sendResponse(AbstractTrackableMessage response) { this.response = response; }
        @Override public void sendResponse(Exception failure) { this.failure = failure; }
    }
    private static final class Provider extends GeneratedFullDataSourceProvider
    {
        int reads;
        private Provider() throws IOException, SQLException { super(null, null); }
        @Override public FullDataSourceV2 get(long pos) { this.reads++; return super.get(pos); }
        @Override public CompletableFuture<com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult> queuePositionForRetrieval(Long pos) { Assert.fail("Cached read requested world generation"); return null; }
        @Override protected FullDataSourceV2 createDataSourceFromDto(FullDataSourceV2DTO dto)
            throws InterruptedException, IOException, com.seibel.distanthorizons.core.util.objects.DataCorruptedException
        { return dto.createUnitTestDataSource(); }
    }
    private static final class Deferred extends AbstractExecutorService
    {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable task) { this.tasks.add(task); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return this.tasks; }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
    }
    private final class Fixture implements AutoCloseable
    {
        final FullDataSourceV2Repo repo = new FullDataSourceV2Repo(AbstractDhRepo.DEFAULT_DATABASE_TYPE, new File(files.newFolder(), "server.sqlite"));
        final Provider provider = allocate(Provider.class);
        final IServerLevelWrapper wrapper = (IServerLevelWrapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IServerLevelWrapper.class},
            (object, method, args) -> method.getName().equals("getDhIdentifier") ? "End-test" : null);
        final IServerPlayerWrapper actor = (IServerPlayerWrapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IServerPlayerWrapper.class},
            (object, method, args) -> method.getName().equals("getLevel") ? this.wrapper : null);
        final NetworkSession session = new NetworkSession(this.actor);
        final ServerPlayerState player = allocate(ServerPlayerState.class);
        final DhServerLevel level = allocate(DhServerLevel.class);
        final Message message = allocate(Message.class);
        final Deferred compression = new Deferred();
        final AtomicInteger releases = new AtomicInteger();
        final FullDataSourceRequestHandler handler;
        final Object previousWorld;
        Fixture() throws Exception
        {
            Field world = SharedApi.class.getDeclaredField("currentWorld"); world.setAccessible(true);
            this.previousWorld = world.get(null); world.set(null, new World());
            set(this.provider, FullDataSourceProviderV2.class, "repo", this.repo);
            set(this.provider, FullDataSourceProviderV2.class, "isShutdownRef", new AtomicBoolean());
            ServerLevelModule module = allocate(ServerLevelModule.class);
            set(module, ServerLevelModule.class, "fullDataFileHandler", this.provider);
            set(this.level, AbstractDhServerLevel.class, "serverLevelWrapper", this.wrapper);
            set(this.level, AbstractDhServerLevel.class, "serverside", module);
            set(this.player, ServerPlayerState.class, "networkSession", this.session);
            set(this.player, ServerPlayerState.class, "sessionConfig", new CachedOnlyConfig());
            set(this.message, FullDataSourceRequestMessage.class, "levelName", "End-test");
            this.message.sectionPos = POS;
            this.message.clientTimestamp = FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP;
            this.message.setSession(this.session);
            this.handler = new FullDataSourceRequestHandler(this.level);
        }
        void readSync() throws Exception
        {
            ServerPlayerState.RateLimiterSet limiters = this.player.new RateLimiterSet();
            Method acquire = FullDataSourceRequestHandler.class.getDeclaredMethod("acquire", ServerPlayerState.class,
                FullDataSourceRequestMessage.class, ServerPlayerState.RateLimiterSet.class,
                com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateAndConcurrencyLimiter.class);
            acquire.setAccessible(true);
            DataSourceRequestGroup.RequestData request = (DataSourceRequestGroup.RequestData) acquire.invoke(
                this.handler, this.player, this.message, limiters, limiters.syncOnLoginRateLimiter);
            Assert.assertNotNull(request);
            RequestQuotaLease quota = request.quota;
            request.quota = new RequestQuotaLease(() -> { quota.finish(null); this.releases.incrementAndGet(); }, () -> { });
            Method read = FullDataSourceRequestHandler.class.getDeclaredMethod("readSync", DataSourceRequestGroup.RequestData.class, AbstractExecutorService.class);
            read.setAccessible(true); read.invoke(this.handler, request, this.compression);
        }
        @Override public void close() throws Exception
        {
            try { this.handler.close(); this.session.close(); this.repo.close(); }
            finally { set(null, SharedApi.class, "currentWorld", this.previousWorld); }
        }
    }

    @Test public void cacheMissReturnsNoUpdateWithoutGenerationOrSyntheticAir() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.readSync();
            Assert.assertTrue(fixture.message.response instanceof FullDataSourceResponseMessage);
            Assert.assertNull(((FullDataSourceResponseMessage) fixture.message.response).payload);
            Assert.assertNull(fixture.message.failure);
            Assert.assertEquals(0, fixture.provider.reads);
            Assert.assertEquals(0, fixture.compression.tasks.size());
            Assert.assertEquals(1, fixture.releases.get());
            Assert.assertFalse(fixture.repo.existsWithKey(POS));
        }
    }
    @Test public void existingRecordIsReadAndScheduledWhileGenerationRemainsDisabled() throws Exception
    {
        try (Fixture fixture = new Fixture(); FullDataSourceV2 data = FullDataSourceV2.createEmpty(POS))
        {
            try (FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateFromDataSource(data, EDhApiDataCompressionMode.LZ4)) { fixture.repo.saveChecked(dto); }
            Assert.assertFalse(fixture.player.sessionConfig.isDistantGenerationEnabled());
            fixture.readSync();
            Assert.assertEquals(1, fixture.provider.reads);
            Assert.assertEquals(1, fixture.compression.tasks.size());
            Assert.assertNull(fixture.message.failure);
            Assert.assertEquals(0, fixture.releases.get());
            fixture.handler.close();
            Assert.assertEquals(1, fixture.releases.get());
            for (Runnable discarded : fixture.compression.tasks) { discarded.run(); }
            Assert.assertEquals(1, fixture.releases.get());
        }
    }
}
