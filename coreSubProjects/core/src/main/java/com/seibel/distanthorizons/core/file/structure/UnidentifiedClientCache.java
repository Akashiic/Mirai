package com.seibel.distanthorizons.core.file.structure;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Objects;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Owned temporary LOD storage when a remote backend has supplied no stable world identity. */
final class UnidentifiedClientCache implements AutoCloseable
{
	// UUID values contain no reference to their world. Retaining a proxy connection
	// must not keep every previously visited WorldClient alive.
	private final ReferenceQueue<Object> releasedWorlds = new ReferenceQueue<>();
	private final Map<WorldIdentityReference, String> namesByWorld = new HashMap<>();
	private Path ownedRoot;
	private boolean closed;

	synchronized File folder(String endpoint, Object world, String dimension)
	{
		if (this.closed) { throw new IllegalStateException("Unidentified client cache is closed"); }
		Objects.requireNonNull(world, "world");
		this.expungeReleasedWorlds();
		try
		{
			if (this.ownedRoot == null) { this.ownedRoot = Files.createTempDirectory("akashic-lod-"); }
			WorldIdentityReference lookup = new WorldIdentityReference(world, null);
			String worldName = this.namesByWorld.get(lookup);
			if (worldName == null)
			{
				worldName = UUID.randomUUID().toString();
				this.namesByWorld.put(new WorldIdentityReference(world, this.releasedWorlds), worldName);
			}
			// The identifier is hashed rather than escaped: distinct unsafe characters must not collapse.
			// 128-bit path components keep legacy Windows/Java 8 paths compact.
			// The full SHA-256 remains available for persistent endpoint namespaces.
			return this.ownedRoot.resolve(endpointKey(endpoint).substring(0, 32)).resolve(worldName)
					.resolve(endpointKey(dimension).substring(0, 32)).toFile();
		}
		catch (IOException error) { throw new UncheckedIOException("Unable to create isolated client LOD cache", error); }
	}

	/** Expunge names only. Files remain owned until provider shutdown permits root cleanup. */
	private void expungeReleasedWorlds()
	{
		Reference<?> released;
		while ((released = this.releasedWorlds.poll()) != null) { this.namesByWorld.remove(released); }
	}

	/** WeakHashMap would use world.equals(); remote world identity must use reference identity. */
	private static final class WorldIdentityReference extends WeakReference<Object>
	{
		private final int identityHash;

		private WorldIdentityReference(Object world, ReferenceQueue<Object> releasedWorlds)
		{
			super(world, releasedWorlds);
			this.identityHash = System.identityHashCode(world);
		}

		@Override public int hashCode() { return this.identityHash; }

		@Override public boolean equals(Object other)
		{
			if (this == other) { return true; }
			if (!(other instanceof WorldIdentityReference)) { return false; }
			Object world = this.get();
			return world != null && world == ((WorldIdentityReference) other).get();
		}
	}

	static String endpointKey(String endpoint)
	{
		try
		{
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(
					(endpoint == null ? "" : endpoint.trim().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
			StringBuilder text = new StringBuilder(hash.length * 2);
			for (byte value : hash)
			{
				text.append(Character.forDigit((value >>> 4) & 15, 16));
				text.append(Character.forDigit(value & 15, 16));
			}
			return text.toString();
		}
		catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is required by the Java runtime", error); }
	}

	@Override
	public synchronized void close() throws IOException
	{
		this.closed = true;
		this.namesByWorld.clear();
		this.expungeReleasedWorlds();
		if (this.ownedRoot == null) { return; }
		// Only traverse our newly allocated OS temporary directory, never old caches or saves.
		// walkFileTree does not follow symbolic links; their targets are left untouched.
		Files.walkFileTree(this.ownedRoot, new SimpleFileVisitor<Path>()
		{
			@Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException
			{
				Files.delete(path);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException
			{
				if (error != null) { throw error; }
				Files.delete(path);
				return FileVisitResult.CONTINUE;
			}
		});
		this.ownedRoot = null;
	}
}
