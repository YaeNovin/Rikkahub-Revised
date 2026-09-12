import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import me.rerere.rikkahub.ui.components.richtext.LatexNormalizationKt;

/** Runs the app's compiled fallback on Android's ICU engine without installing
 * a test app, starting activities or touching any application data. */
public final class AndroidLatexProbe {
    public static void main(String[] args) {
        boolean rejected = false;
        try { Pattern.compile("\\\\(?:left|right|,|;|!|quad|qquad)\\b?"); }
        catch (PatternSyntaxException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Expected Android ICU to reject the reported expression");
        check("( x ) y z", "\\left( x \\right)\\,y\\;z\\!\\quad\\qquad");
        check("leftover rightward quadric", "\\leftover \\rightward \\quadric");
        check("(α)/(β) ≤ √(x)", "\\frac{\\alpha}{\\beta} \\le \\sqrt{\\mathrm{x}}");
        check("unsupported(x)", "\\unsupported(x)");
        check("a b c", "a\\,b\\;c");
        check("← x →", "\\leftarrow x \\rightarrow");
        check("normal text", "normal text");
        System.out.println("PASS: reported regex rejected by Android ICU; 7 app LaTeX fallback cases passed.");
    }
    private static void check(String expected, String input) {
        String actual = LatexNormalizationKt.latexReadableFallback(input);
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
