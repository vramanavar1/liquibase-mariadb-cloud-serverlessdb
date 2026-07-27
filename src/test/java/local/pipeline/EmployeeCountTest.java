package local.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the seeded <b>Employee</b> row count only.
 *
 * <p>Deliberately independent of {@link DepartmentCountTest}: it holds no shared
 * state and opens its own connection, so its outcome never influences the
 * department check. Expected value comes from the {@code expected.employee.count}
 * property of the active Maven profile.
 *
 * <p>Runs in the {@code test} phase, after the dropAll/update executions bound
 * to {@code process-test-resources}:  {@code mvn -PTest test}
 */
@DisplayName("Employee row count")
@ExtendWith(TestOutcomeLogger.class)
class EmployeeCountTest {

    @Test
    @DisplayName("matches the expected number of seeded employees")
    void employeeRowCountMatchesExpected() {
        int expected = DbTestSupport.expectedCount("expected.employee.count");
        int actual = DbTestSupport.countRows("Employee");

        assertEquals(expected, actual, "Employee row count");
    }
}
