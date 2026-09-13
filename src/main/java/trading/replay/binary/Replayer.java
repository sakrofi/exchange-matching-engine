package trading.replay.binary;

import trading.carriers.EventType;
import trading.matcher.Matcher;

import java.io.IOException;
import java.nio.file.Path;

public final class Replayer {

    private final Matcher matcher;

    public Replayer(Matcher matcher) {
        this.matcher = matcher;
    }

    private static final long MIN_PRICE = 1;
    private static final long MAX_PRICE = 3_000_000;

    private long skippedOutOfRange;
    private long processed;

    public void run(Path binaryFile) throws IOException {

        try (BinaryReader reader = new BinaryReader(binaryFile)) {
            while (reader.hasNext()) {
                long timestamp = reader.readTimestamp();
                long orderId = reader.readOrderId();
                long price = reader.readPrice();
                int quantity = reader.readQuantity();
                byte eventType = reader.readEventType();
                byte side = reader.readSide();

                reader.skipPadding();
                processRecord(timestamp, orderId, price, quantity, eventType, side);
            }
        }
    }

    private void processRecord(long timestamp, long orderId, long price, int quantity, byte eventType, byte side) {
        if (eventType == 0 && (price < MIN_PRICE || price > MAX_PRICE)) {
            skippedOutOfRange++;
            return;

        }

        switch (eventType) {
            case 0 -> {
                matcher.execute(
                        orderId,
                        side == 1,
                        price,
                        quantity,
                        EventType.NEW,
                        timestamp);
                processed++;
            }

            case 1 -> {
                matcher.execute(
                        orderId,
                        false, // unused
                        0, // unused
                        0, // unused
                        EventType.CANCEL,
                        timestamp);
                processed++;
            }

            case 2 -> {
                matcher.execute(
                        orderId,
                        false, // unused
                        0, // unused
                        quantity, // qty
                        EventType.MODIFY_INPLACE,
                        timestamp);
                processed++;
            }

            default -> throw new IllegalArgumentException(
                    "Unknown event type: " + eventType);
        }
    }

    public long getProcessed() {
        return processed;
    }
    public long getSkippedOutOfRange() {
        return skippedOutOfRange;
    }
}