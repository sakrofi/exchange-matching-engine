package trading.replay.binary;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
public final class BinaryReader implements AutoCloseable {

    private static final int RECORD_SIZE = 32;
    private static final long MAX_MAP_SIZE = 1L << 30;

    private final FileChannel channel;
    private final MappedByteBuffer buffer;

    public BinaryReader(Path path) throws IOException {
        channel = FileChannel.open(path, StandardOpenOption.READ);

        long fileSize = channel.size();

        if (fileSize > MAX_MAP_SIZE) {
            throw new IOException("File too large to map: path=" + path + " size=" + fileSize + " maxAllowed=" + MAX_MAP_SIZE);
        }
        if (fileSize % RECORD_SIZE != 0) {
            throw new IOException("Invalid record alignment for file " + path + ": size=" + fileSize + " (expected multiple of " + RECORD_SIZE + ")");
        }
        buffer = channel.map(
                FileChannel.MapMode.READ_ONLY,
                0,
                fileSize);
        buffer.order(ByteOrder.BIG_ENDIAN);
    }

    public boolean hasNext() {
        return buffer.remaining() >= RECORD_SIZE;
    }
    public long readTimestamp() {
        return buffer.getLong();
    }
    public long readOrderId() {
        return buffer.getLong();
    }
    public long readPrice() {
        return buffer.getInt() & 0xFFFFFFFFL;
    }
    public int readQuantity() {
        return buffer.getInt();
    }
    public byte readEventType() {
        return buffer.get();
    }
    public byte readSide() {
        return buffer.get();
    }
    public void skipPadding() {
        buffer.getInt();
        buffer.getShort();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}