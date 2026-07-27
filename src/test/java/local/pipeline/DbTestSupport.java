package local.pipeline;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared JDBC plumbing for the row-count checks.
 *
 * <p>Every failure mode - missing configuration, unreachable database, bad
 * credentials, missing table - is converted into a descriptive assertion
 * failure rather than a propagated exception. Surefire therefore reports a
 * plain PASSED/FAILED result with a one-line reason instead of a Java stack
 * trace. {@code Assertions.fail(..)} is declared to return a value, so it can
 * be used directly as the result expression.
 *
 * <p>Each call opens and closes its own connection, which is what keeps the
 * individual test classes independent: one failing check never affects the
 * outcome of the other.
 */
final class DbTestSupport {

    private DbTestSupport() {
        // static helper
    }

    /**
     * Counts the rows in {@code table}, or fails the calling test with a
     * readable reason. Table names are compile-time constants at every call
     * site, never user input.
     */
    static int countRows(String table) {
        String url = System.getProperty("db.url");
        String user = System.getenv("LIQUIBASE_COMMAND_USERNAME");
        String password = System.getenv("LIQUIBASE_COMMAND_PASSWORD");

        if (isBlank(url)) {
            return fail("Configuration missing: system property 'db.url' is not set. Run with -PTest.");
        }
        if (isBlank(user)) {
            return fail("Configuration missing: environment variable LIQUIBASE_COMMAND_USERNAME is not set.");
        }
        if (isBlank(password)) {
            return fail("Configuration missing: environment variable LIQUIBASE_COMMAND_PASSWORD is not set.");
        }

        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {

            if (!rs.next()) {
                return fail("Could not read `" + table + "`: COUNT(*) returned no row.");
            }
            return rs.getInt(1);

        } catch (SQLException e) {
            return fail("Could not read `" + table + "`: " + e.getMessage());
        }
    }

    /** Reads an expected-count system property, or fails with a readable reason. */
    static int expectedCount(String property) {
        Integer value = Integer.getInteger(property);
        if (value == null) {
            return fail("Configuration missing: system property '" + property + "' is not set. Run with -PTest.");
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
