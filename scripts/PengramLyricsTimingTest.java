import org.telegram.messenger.PengramLyricsTiming;

public class PengramLyricsTimingTest {
    private static void close(float actual, float expected) {
        if (Math.abs(actual - expected) > 0.0001f) {
            throw new AssertionError("Expected " + expected + " got " + actual);
        }
    }

    public static void main(String[] args) {
        // On by default is a preference decision. Off MUST leave original LRC
        // timestamps untouched even for a half-speed or double-speed recording.
        close(PengramLyricsTiming.scale(false, true, 60_000, 120_000, 115_000), 1f);
        close(PengramLyricsTiming.scale(true, true, 60_000, 120_000, 115_000), .5f);
        close(PengramLyricsTiming.scale(true, true, 180_000, 120_000, 115_000), 1.5f);
        close(PengramLyricsTiming.scale(true, false, 60_000, 120_000, 0), 1f);
        close(PengramLyricsTiming.scale(true, true, 120_000, 0, 98_000), 120_000f / 101_000f);
        close(PengramLyricsTiming.scale(true, true, 120_000, 0, 10_000), 1f);
        close(PengramLyricsTiming.scale(true, true, 120_000, 120_000, 115_000), 1f);
        if (PengramLyricsTiming.sample(10_439, false, true) != 10_000
                || PengramLyricsTiming.sample(10_501, false, true) != 10_500
                || PengramLyricsTiming.sample(10_439, true, true) != 10_439
                || PengramLyricsTiming.sample(10_439, false, false) != 10_439) {
            throw new AssertionError("Stepped mode must be visible even with 17 ms player updates");
        }
        if (PengramLyricsTiming.position(10_000, 1_000, 1_800, false, true, 1.5f) != 10_000
                || PengramLyricsTiming.position(10_000, 1_000, 1_800, true, false, 1.5f) != 10_000
                || PengramLyricsTiming.position(10_000, 1_000, 1_800, true, true, 1.5f) != 11_200
                || PengramLyricsTiming.position(10_000, 1_000, 4_100, true, true, 1f) != 10_000) {
            throw new AssertionError("Smooth/stepped timing or pause handling is broken");
        }
        close(PengramLyricsTiming.seekFraction(60_000, .5f, 60_000), .5f);
        close(PengramLyricsTiming.seekFraction(60_000, 1f, 60_000), 1f);
        System.out.println("Pengram lyric timing: OK");
    }
}
