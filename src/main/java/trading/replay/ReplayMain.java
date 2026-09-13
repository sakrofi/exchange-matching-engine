package trading.replay;

import trading.books.*;
import trading.matcher.FifoMatcher;
import trading.matcher.Matcher;
import trading.replay.binary.Replayer;
import trading.sinks.ListTradeSink;
import trading.sinks.NullTradeSink;
import trading.sinks.RejectReason;
import trading.sinks.TradeSink;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public final class ReplayMain {


    public enum SinkType {
        NULL,
        LIST
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println(
                    "Usage: ReplayMain <file.bin> <order-book> <sink>");
            System.exit(1);
        }

        Path path = Paths.get(args[0]);

        OrderBook orderBook = createOrderBook(args[1]);

        SinkType sinkType;
        try {
            sinkType = SinkType.valueOf(args[2].toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown sink: " + args[2] + " (expected NULL or LIST)");
        }

        TradeSink sink = switch (sinkType) {
            case NULL -> new NullTradeSink();
            case LIST -> new ListTradeSink();
        };

        Matcher matcher = new FifoMatcher(orderBook, sink);
        Replayer replayer = new Replayer(matcher);

        try {
            replayer.run(path);

            System.out.printf("Processed: %,d%n", replayer.getProcessed());
            System.out.printf("Skipped (out of range): %,d%n",
                    replayer.getSkippedOutOfRange());

            if (sink instanceof ListTradeSink listSink) {
                printEvents(listSink);
                printSummary(listSink);
                printRejectSummary(listSink);
            }

        } catch (IOException e) {
            System.err.println("Replayer failed: " + e.getMessage());
            System.exit(1);
        }
    }


    private static void printEvents(TradeSink sink) {
        if (!(sink instanceof ListTradeSink listSink)) {
            return;
        }
        List<ListTradeSink.SinkEvent> events = listSink.getEvents();
        int EVENT_LOG_LIMIT = 100;
        int limit = Math.min(EVENT_LOG_LIMIT, events.size());

        for (int i = 0; i < limit; i++) {
            System.out.printf("%d: %s%n", i, events.get(i));
        }
    }

    private static void printSummary(ListTradeSink sink) {
        System.out.printf("Events: %,d%n", sink.getEvents().size());
        System.out.printf("Trades: %,d%n", sink.getTrades().size());
        System.out.printf("Cancels: %,d%n", sink.getCancels().size());
        System.out.printf("Modifications: %,d%n", sink.getModifies().size());
        System.out.printf("Rejects: %,d%n", sink.getRejects().size());
    }



    private static void printRejectSummary(ListTradeSink sink) {
        Map<RejectReason, Long> counts = sink.getRejects()
                .stream()
                .collect(Collectors.groupingBy(
                        ListTradeSink.RejectEvent::reason,
                        Collectors.counting()));

        for (var entry : counts.entrySet()) {
            System.out.printf(
                    "Reject %-25s %,d%n",
                    entry.getKey(),
                    entry.getValue());
        }
    }


    private static OrderBook createOrderBook(String choice) {

        return switch (choice) {

            case "BitwiseOrderBook" ->
                    new BitwiseOrderbook( 1, 3_000_000, 2_000_000);

            case "LinearSearchOrderBook" ->
                    new LinearSearchOrderBook( 1, 3_000_000, 2_000_000);

            case "RedBlackTreeOrderBook" ->
                    new RedBlackTreeOrderBook(1_600_000);

            case "PooledRedBlackTreeOrderBook" ->
                    new PooledRedBlackTreeOrderBook(2_000_000, 1_600_000);

            default -> throw new IllegalArgumentException(
                    "Unknown book: " + choice);
        };
    }
}