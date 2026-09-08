package com.seibel.distanthorizons.core.util.objects.dataStreams;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounds expanded bytes, including primitive reads performed by DataInputStream. */
public final class BoundedDecodeInputStream extends FilterInputStream
{
	private long remaining;
	public BoundedDecodeInputStream(InputStream input, long limit)
	{
		super(input);
		if (limit < 0) { throw new IllegalArgumentException("Negative decode budget"); }
		this.remaining = limit;
	}

	@Override public int read() throws IOException
	{
		int value = this.in.read();
		if (value >= 0 && --this.remaining < 0) { throw new IOException("LOD decompression budget exceeded"); }
		return value;
	}

	@Override public int read(byte[] buffer, int offset, int length) throws IOException
	{
		if (offset < 0 || length < 0 || offset > buffer.length - length) { throw new IndexOutOfBoundsException(); }
		if (length == 0) { return 0; }
		if (this.remaining < 0) { throw new IOException("LOD decompression budget exceeded"); }
		int count = this.in.read(buffer, offset, (int) Math.min(length, this.remaining + 1));
		if (count > 0 && (this.remaining -= count) < 0) { throw new IOException("LOD decompression budget exceeded"); }
		return count;
	}

	@Override public long skip(long amount) throws IOException
	{
		if (amount <= 0) { return 0; }
		byte[] scratch = new byte[(int) Math.min(amount, 4096)];
		long skipped = 0;
		while (skipped < amount)
		{
			int count = this.read(scratch, 0, (int) Math.min(scratch.length, amount - skipped));
			if (count < 0) { break; }
			skipped += count;
		}
		return skipped;
	}

	@Override public boolean markSupported() { return false; }
	@Override public void mark(int readLimit) { }
	@Override public void reset() throws IOException { throw new IOException("Decode stream cannot rewind its budget"); }
}
