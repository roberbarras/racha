package io.github.roberbarras.racha;

import static com.tngtech.archunit.library.Architectures.onionArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Guards the hexagonal layout used by every bounded context:
 * {@code <context>.domain} (model + outbound ports), {@code <context>.application} (use cases, inbound ports)
 * and {@code <context>.adapter.<technology>} (driving and driven adapters).
 */
@AnalyzeClasses(packages = "io.github.roberbarras.racha", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule layersRespectDependencyDirection = onionArchitecture()
            .domainModels("..domain..")
            .applicationServices("..application..")
            .adapter("web", "..adapter.web..")
            .adapter("persistence", "..adapter.persistence..")
            .adapter("security", "..adapter.security..")
            .withOptionalLayers(true);

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "org.hibernate..", "com.fasterxml..", "tools.jackson..")
            .allowEmptyShould(true);
}
