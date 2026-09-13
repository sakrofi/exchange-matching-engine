package trading.replay.itch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


public final class ItchToBinary {

    private ItchToBinary() {
    }


    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: ItchToBinary <input .NASDAQ_ITCH50 file> <output .bin file>");
            System.exit(1);
            return;
        }

        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        try (ItchParser parser = new ItchParser()) {
            long startNanos = System.nanoTime();
            parser.parse(input, output);
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

            long inputBytes = Files.size(input);
            long outputBytes = Files.size(output);

            System.out.printf(
                    "%s (%,d bytes) -> %s (%,d bytes) in %,d ms | %,d AAPL records extracted (%.1f MB/s)%n",
                    input, inputBytes, output, outputBytes, elapsedMillis, parser.getExtractedCount(),
                    elapsedMillis == 0 ? 0.0 : (inputBytes / 1_048_576.0) / (elapsedMillis / 1000.0));
        }
    }
}