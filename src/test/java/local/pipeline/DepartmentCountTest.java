package local.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the seeded <b>Department</b> row count only.
 *
 * <p>Deliberately independent of {@link EmployeeCountTest}: it holds no shared
 * state and opens its own connection, so its outcome never influences the
 * employee check. Expected value comes from the {@code expected.department.count}
 * property of the active Maven profile.
 *
 * <p>Runs in the {@code test} phase, after the dropAll/update executions bound
 * to {@code process-test-resources}:  {@code mvn -PTest test}
 */
@DisplayName("Department row count")
@ExtendWith(TestOutcomeLogger.class)
class DepartmentCountTest {

    @Test
    @DisplayName("matches the expected number of seeded departments")
    void departmentRowCountMatchesExpected() {
        int expected = DbTestSupport.expectedCount("expected.department.count");
        int actual = DbTestSupport.countRows("Department");

        assertEquals(expected, actual, "Department row count");
    }
}
