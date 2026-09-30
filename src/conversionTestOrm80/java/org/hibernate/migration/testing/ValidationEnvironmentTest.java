package org.hibernate.migration.testing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/// Checks the declared source API environment.
/// @author Steve Ebersole
class ValidationEnvironmentTest {
    @Test
    void sourceRuntimeUsesDeclaredJpa() {
        var environments = ApiValidation.environments();
        String expectedJar = "jakarta.persistence-api-"
                + environments.value(environments.primary().input() + ".jpaVersion") + ".jar";
        var resource = jakarta.persistence.EntityManager.class.getResource("EntityManager.class");
        assertNotNull(resource, "EntityManager class resource");
        assertTrue(resource.toString().contains(expectedJar), "Expected " + expectedJar + " but loaded " + resource);
    }
}
