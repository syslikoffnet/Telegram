import org.telegram.ui.Components.PengramGraphemes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

/** Исполняет именно production Java, когда доступен javac; Android не требуется. */
public final class PengramGraphemesCheck {
    private static void check(String text, int expected) {
        int count = 0;
        for (int start = 0; start < text.length();) {
            int end = PengramGraphemes.clusterEnd(text, start);
            if (end <= start || end > text.length()) throw new AssertionError("boundary");
            if (PengramGraphemes.previous(text, end) != start) throw new AssertionError("previous");
            for (int i = start + 1; i < end; i++) {
                if (PengramGraphemes.clusterStart(text, i) != start) throw new AssertionError("partial cluster");
            }
            start = end;
            count++;
        }
        if (count != expected) throw new AssertionError(text + ": " + count + " != " + expected);
    }
    public static void main(String[] args) throws Exception {
        String[] single = {"👍", "👍🏽", "👨‍👩‍👧‍👦", "🏳️‍🌈", "🇷🇺", "1️⃣", "❤️", "e\u0301", "क्ष", "क्‍ष", "각"};
        for (String text : single) check(text, 1);
        check("привет 👋 мир 🇷🇺", 14);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 30; i++) many.append("👍🏽");
        check(many.toString(), 30);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 5000; i++) longText.append('a');
        longText.append("👨‍👩‍👧‍👦");
        if (PengramGraphemes.clusterStart(longText, longText.length() - 1) != 5000) throw new AssertionError("large offset");
        int cases = 0;
        for (String raw : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            String line = raw.split("#", 2)[0].trim();
            if (line.isEmpty()) continue;
            StringBuilder text = new StringBuilder();
            ArrayList<Integer> expected = new ArrayList<>();
            for (String token : line.split("\\s+")) {
                if (token.equals("÷")) expected.add(text.length());
                else if (!token.equals("×")) text.appendCodePoint(Integer.parseInt(token, 16));
            }
            ArrayList<Integer> actual = new ArrayList<>();
            actual.add(0);
            for (int start = 0; start < text.length();) {
                start = PengramGraphemes.clusterEnd(text, start);
                actual.add(start);
            }
            if (!actual.equals(expected)) throw new AssertionError(raw + "\n" + actual + " != " + expected);
            cases++;
        }
        System.out.println("Production Java UAX #29: " + cases + " official vectors and Pengram fixtures PASS");
    }
}
