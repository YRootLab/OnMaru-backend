package com.yrootlab.onmaru.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@AnalyzeClasses(packages = "com.yrootlab.onmaru")
class ModuleBoundaryArchUnitTests {

    @ArchTest
    static final ArchRule core_must_not_import_spring_jpa_or_reactor =
            noClasses()
                    .that().resideInAPackage("com.yrootlab.onmaru..")
                    .and().resideOutsideOfPackage("com.yrootlab.onmaru.architecture.fixture..")
                    .and().resideInAPackage("..core..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "reactor..")
                    .because("core는 Spring, persistence, reactive framework 타입과 독립적이어야 한다")
                    .allowEmptyShould(true);

    @Test
    void productionModulesStayAcyclic() {
        JavaClasses classes = importedProduction("com.yrootlab.onmaru");

        checkDocumentedModuleDirection(classes, "com.yrootlab.onmaru");
        checkCoreDoesNotReachAcrossModules(classes, "com.yrootlab.onmaru");
        checkAppBridgeUsesModuleApiOnly(classes, "com.yrootlab.onmaru");
        moduleSlices("com.yrootlab.onmaru.(*)..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void frameworkImportFixtureFailsTheCoreRule() {
        JavaClasses classes = imported("com.yrootlab.onmaru.architecture.fixture.framework");

        assertThatThrownBy(() -> coreMustNotImportFrameworks("..fixture.framework..").check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("org.springframework");
    }

    @Test
    void moduleDagFixtureAcceptsDocumentedDependencyDirection() {
        JavaClasses classes = imported("com.yrootlab.onmaru.architecture.fixture.module.clean");

        checkDocumentedModuleDirection(classes, "..fixture.module.clean");
        checkCoreDoesNotReachAcrossModules(classes, "..fixture.module.clean");
        checkAppBridgeUsesModuleApiOnly(classes, "..fixture.module.clean");
        moduleSlices("..fixture.module.clean.(*)..").check(classes);
    }

    @Test
    void directCrossModuleCoreFixtureFailsConsumerOwnedPortRule() {
        JavaClasses classes = imported("com.yrootlab.onmaru.architecture.fixture.module.direct");

        assertThatThrownBy(() -> checkCoreDoesNotReachAcrossModules(classes, "..fixture.module.direct"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("다른 module package");
    }

    @Test
    void bridgeInternalImportFixtureFailsApiOnlyRule() {
        JavaClasses classes = imported("com.yrootlab.onmaru.architecture.fixture.module.bridgeviolation");

        assertThatThrownBy(() -> checkAppBridgeUsesModuleApiOnly(classes, "..fixture.module.bridgeviolation"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("공개 경계만 조합");
    }

    @Test
    void moduleCycleFixtureFailsModuleDagRule() {
        JavaClasses classes = imported("com.yrootlab.onmaru.architecture.fixture.module.cycle");

        assertThatThrownBy(() -> moduleSlices("..fixture.module.cycle.(*)..").check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cycle detected");
    }

    private static JavaClasses imported(String packageName) {
        return new ClassFileImporter().importPackages(packageName);
    }

    private static JavaClasses importedProduction(String packageName) {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(packageName);
    }

    private static ArchRule coreMustNotImportFrameworks(String packagePattern) {
        return noClasses()
                .that().resideInAPackage(packagePattern)
                .and().resideInAPackage("..core..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "reactor..")
                .because("core는 Spring, persistence, reactive framework 타입과 독립적이어야 한다");
    }

    private static void checkDocumentedModuleDirection(JavaClasses classes, String packagePattern) {
        noClasses()
                .that().resideInAPackage(packagePattern + ".catalog..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        packagePattern + ".discovery..",
                        packagePattern + ".journey..",
                        packagePattern + ".community..")
                .because("catalog는 상위 module에 의존하지 않는다")
                .allowEmptyShould(true)
                .check(classes);

        noClasses()
                .that().resideInAPackage(packagePattern + ".discovery..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        packagePattern + ".journey..",
                        packagePattern + ".community..")
                .because("discovery는 journey나 community에 의존하지 않는다")
                .allowEmptyShould(true)
                .check(classes);

        noClasses()
                .that().resideInAPackage(packagePattern + ".community..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        packagePattern + ".discovery..",
                        packagePattern + ".journey..")
                .because("community는 catalog port만 소비하고 discovery/journey에 의존하지 않는다")
                .allowEmptyShould(true)
                .check(classes);
    }

    private static void checkCoreDoesNotReachAcrossModules(JavaClasses classes, String packagePattern) {
        String[] modules = {"catalog", "discovery", "journey", "community"};

        for (String module : modules) {
            noClasses()
                    .that().resideInAPackage(packagePattern + "." + module + ".core..")
                    .should().dependOnClassesThat().resideInAnyPackage(otherModulePackages(packagePattern, module))
                    .because("core는 consumer-owned port를 사용하고 다른 module package를 직접 import하지 않는다")
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    private static void checkAppBridgeUsesModuleApiOnly(JavaClasses classes, String packagePattern) {
        noClasses()
                .that().resideInAPackage(packagePattern + ".app.bridge..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        packagePattern + ".catalog.core..",
                        packagePattern + ".catalog.application..",
                        packagePattern + ".catalog.adapter..",
                        packagePattern + ".discovery.core..",
                        packagePattern + ".discovery.application..",
                        packagePattern + ".discovery.adapter..",
                        packagePattern + ".journey.core..",
                        packagePattern + ".journey.application..",
                        packagePattern + ".journey.adapter..",
                        packagePattern + ".community.core..",
                        packagePattern + ".community.application..",
                        packagePattern + ".community.adapter..")
                .because("app bridge는 다른 module의 api/port 같은 공개 경계만 조합한다")
                .allowEmptyShould(true)
                .check(classes);
    }

    private static String[] otherModulePackages(String packagePattern, String currentModule) {
        return java.util.stream.Stream.of("catalog", "discovery", "journey", "community")
                .filter(module -> !module.equals(currentModule))
                .map(module -> packagePattern + "." + module + "..")
                .toArray(String[]::new);
    }

    private static ArchRule moduleSlices(String packagePattern) {
        return slices().matching(packagePattern)
                .should().beFreeOfCycles()
                .because("module DAG는 순환 의존을 허용하지 않는다");
    }
}
