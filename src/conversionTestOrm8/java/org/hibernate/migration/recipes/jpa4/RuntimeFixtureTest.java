/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes.jpa4;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/// Validates the reusable integration fixture without publishing handoff files.
/// @author Steve Ebersole
class RuntimeFixtureTest {
    @ParameterizedTest @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void validatesRuntimeFixture(String api) throws Exception {
        String input = RuntimeFixture.source();
        var result = ApiValidation.run(ApiValidation.composite("org.hibernate.migration.recipes.jpa4"), Map.of("fixture/Migrated.java", input), api);
        assertTrue(result.skipped().isEmpty());
        String output = result.files().get("fixture/Migrated.java");
        assertEquals(4, HardeningTest.occurrences(output, "@NamedStatement("));
        assertEquals(3, HardeningTest.occurrences(output, "@NamedNativeStatement("));
    }
}
