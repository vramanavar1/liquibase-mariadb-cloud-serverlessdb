package local.pipeline;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.Optional;

/**
 * Prints one clean {@code PASSED} / {@code FAILED} line per test.
 *
 * <p>Surefire's own output carries the framework's assertion type and a stack
 * frame. This watcher adds a plain-language summary so a run reports nothing but
 * the outcome and, when it fails, the reason:
 *
 * <pre>
 *   [TEST] Department row count :: FAILED - expected: &lt;4&gt; but was: &lt;5&gt;
 *   [TEST] Employee row count   :: PASSED
 * </pre>
 *
 * <p>Registered per class with {@code @ExtendWith}. Because it only observes
 * outcomes, it cannot change whether a test passes.
 */
class TestOutcomeLogger implements TestWatcher {

    @Override
    public void testSuccessful(ExtensionContext context) {
        System.out.println("[TEST] " + label(context) + " :: PASSED");
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        System.out.println("[TEST] " + label(context) + " :: FAILED - " + reason(cause));
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        System.out.println("[TEST] " + label(context) + " :: SKIPPED - " + reason(cause));
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        System.out.println("[TEST] " + label(context) + " :: SKIPPED - " + reason.orElse("disabled"));
    }

    /** "<class display name> <method display name>", e.g. "Department row count matches ...". */
    private static String label(ExtensionContext context) {
        String parent = context.getParent().map(ExtensionContext::getDisplayName).orElse("");
        return (parent + " " + context.getDisplayName()).trim();
    }

    /** Message only - never the exception type or a stack trace. */
    private static String reason(Throwable cause) {
        if (cause == null) {
            return "no reason reported";
        }
        String message = cause.getMessage();
        return (message == null || message.isBlank()) ? cause.getClass().getSimpleName() : message;
    }
}
