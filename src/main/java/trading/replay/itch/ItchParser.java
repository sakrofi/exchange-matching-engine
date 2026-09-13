package trading.replay.itch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class ItchParser implements AutoCloseable {

    public static final int RECORD_SIZE = 32;

    public static final byte EVT_NEW = 0;
    public static final byte EVT_DELETE = 1;
    public static final byte EVT_CANCEL = 2;

    private static final long CHUNK_SIZE = 1L << 30; // 1 GB processed per mmap window
    private static final int MAX_MSG_SIZE = 64;       // padding for a message straddling the window boundary
    // (largest ITCH 5.0 message + 2-byte length prefix is 52 bytes)
    private static final int OUT_BUFFER_SIZE = 1 << 20; // 1 MB batched-write buffer (~26k records)

    // "AAPL" (0x41 0x41 0x50 0x4C), right-padded with ASCII spaces (0x20) to 8 bytes.
    private static final long AAPL_SYMBOL_BYTES = 0x4141504C20202020L;

    private int aaplStockLocate = -1; // sentinel: no valid locate is negative
    private long extractedCount = 0;

    public long getExtractedCount() {
        return extractedCount;
    }



    public void close() throws IOException {
        // intentionally empty: resource cleanup is handled by callers; kept to satisfy AutoCloseable
    }

    public void parse(Path itchFile, Path binPath) throws IOException {
        ByteBuffer outRecord = ByteBuffer.allocate(OUT_BUFFER_SIZE).order(ByteOrder.BIG_ENDIAN);

        try (FileChannel inChannel = FileChannel.open(
                itchFile,
                StandardOpenOption.READ);
             FileChannel outChannel = FileChannel.open(
                     binPath,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {

            long fileSize = inChannel.size();
            long filePosition = 0;

            while (filePosition < fileSize) {
                long remainingInFile = fileSize - filePosition;
                long windowSize = Math.min(CHUNK_SIZE + MAX_MSG_SIZE, remainingInFile);
                long processLimit = Math.min(CHUNK_SIZE, remainingInFile);

                MappedByteBuffer buffer = inChannel.map(FileChannel.MapMode.READ_ONLY, filePosition, windowSize);
                buffer.order(ByteOrder.BIG_ENDIAN);

                parseChunk(buffer, processLimit, outRecord, outChannel);


                filePosition += buffer.position();
            }

            if (outRecord.position() > 0) {
                outRecord.flip();
                recordToFile(outChannel, outRecord);
            }
        }

    }

    private void parseChunk(MappedByteBuffer buffer, long processLimit, ByteBuffer outRecord, FileChannel outChannel)
            throws IOException {
        while (buffer.position() < processLimit) {
            int msgLen = buffer.getShort() & 0xFFFF;
            int msgStart = buffer.position();
            byte type = buffer.get(msgStart);

            switch (type) {
                case 'R' -> handleStockDirectory(buffer, msgStart);
                case 'A', 'F' -> handleAdd(buffer, msgStart, outRecord, outChannel);
                case 'D' -> handleDelete(buffer, msgStart, outRecord, outChannel);
                case 'X' -> handleCancel(buffer, msgStart, outRecord, outChannel);
                default -> { }
            }

            // Resync using the wire's own length rather than a hardcoded per-type table
            buffer.position(msgStart + msgLen);
        }
    }


    private void handleStockDirectory(MappedByteBuffer buffer, int msgStart) {
        buffer.position(msgStart + 1);
        int stockLocate = buffer.getShort() & 0xFFFF;      // offset 1..3
        buffer.position(msgStart + 11);                     // skip tracking number(2) + timestamp(6)
        long symbol = buffer.getLong();                      // Stock, offset 11..19

/*
        if (stockLocate == 14 || symbol == AAPL_SYMBOL_BYTES) {
            System.out.printf(
                    "STOCK DIRECTORY: locate=%d symbol='%s' raw=%016X%n",
                    stockLocate,
                    symbolToString(symbol),
                    symbol
            );
        }
 */

        if (symbol == AAPL_SYMBOL_BYTES) {
            aaplStockLocate = stockLocate;
        }
    }

    // Handles both 'A' (Add Order) and 'F' (Add Order w/ MPID Attribution)
    private void handleAdd(MappedByteBuffer buffer, int msgStart, ByteBuffer outRecord, FileChannel outChannel)
            throws IOException {
        buffer.position(msgStart + 1);
        int stockLocate = buffer.getShort() & 0xFFFF;       // offset 1..3
        if (stockLocate != aaplStockLocate) {
            return;
        }
        buffer.position(msgStart + 5);                       // skip tracking number(2), land on timestamp
        long timestamp = read48BitTimestamp(buffer);          // offset 5..11
        long orderId = buffer.getLong();                       // offset 11..19
        byte side = buffer.get();                               // offset 19..20 'B' or 'S'
        int quantity = buffer.getInt();                        // offset 20..24
        buffer.position(msgStart + 32);                        // skip Stock(8), offset 24..32
        int price = buffer.getInt();                            // offset 32..36

        writeRecord(outChannel, outRecord, timestamp, orderId, price, quantity,
                EVT_NEW, (byte) (side == 'B' ? 1 : 0));
        extractedCount++;
    }

    private void handleDelete(MappedByteBuffer buffer, int msgStart, ByteBuffer outRecord, FileChannel outChannel)
            throws IOException {
        buffer.position(msgStart + 1);
        int stockLocate = buffer.getShort() & 0xFFFF;
        if (stockLocate != aaplStockLocate) {
            return;
        }
        buffer.position(msgStart + 5);
        long timestamp = read48BitTimestamp(buffer);
        long orderId = buffer.getLong();                       // offset 11..19

        writeRecord(outChannel, outRecord, timestamp, orderId, 0, 0, EVT_DELETE, (byte) 0);
        extractedCount++;
    }

    private void handleCancel(MappedByteBuffer buffer, int msgStart, ByteBuffer outRecord, FileChannel outChannel)
            throws IOException {
        buffer.position(msgStart + 1);
        int stockLocate = buffer.getShort() & 0xFFFF;
        if (stockLocate != aaplStockLocate) {
            return;
        }
        buffer.position(msgStart + 5);
        long timestamp = read48BitTimestamp(buffer);
        long orderId = buffer.getLong();                       // offset 11..19
        int cancelledShares = buffer.getInt();                 // offset 19..23

        writeRecord(outChannel, outRecord, timestamp, orderId, 0, cancelledShares, EVT_CANCEL, (byte) 0);
        extractedCount++;
    }

    private void writeRecord(FileChannel outChannel, ByteBuffer outRecord, long timestamp, long orderId,
                             int price, int quantity, byte eventType, byte side) throws IOException {
        if (outRecord.remaining() < RECORD_SIZE) {
            outRecord.flip();
            recordToFile(outChannel, outRecord);
            outRecord.clear();
        }

        outRecord.putLong(timestamp);
        outRecord.putLong(orderId);
        outRecord.putInt(price);
        outRecord.putInt(quantity);
        outRecord.put(eventType);
        outRecord.put(side);
        outRecord.putInt(0); // padding
        outRecord.putShort((short) 0); // padding
    }

    private static void recordToFile(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }







    private static long read48BitTimestamp(ByteBuffer buffer) {
        return ((buffer.get() & 0xFFL) << 40)
                | ((buffer.get() & 0xFFL) << 32)
                | ((buffer.get() & 0xFFL) << 24)
                | ((buffer.get() & 0xFFL) << 16)
                | ((buffer.get() & 0xFFL) << 8)
                | (buffer.get() & 0xFFL);
    }


        /*
    private static String symbolToString(long value) {
        byte[] bytes = ByteBuffer
                .allocate(Long.BYTES)
                .putLong(value)
                .array();

        return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
    }
     */

}