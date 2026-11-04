package com.codewitheli.vaultlocal.app;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Hexagonal dependency rules (DESIGN.md §2) and the "no network code" rule (§5.1, Phase 3),
 * checked against the compiled production classes of every module.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        // The other modules arrive as project JARs on the test classpath, so JARs must be included.
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.codewitheli.vaultlocal");
    }

    @Test
    void everyModuleIsActuallyChecked() {
        for (String name : List.of(
                "com.codewitheli.vaultlocal.domain.secret.Secret",
                "com.codewitheli.vaultlocal.application.service.VaultService",
                "com.codewitheli.vaultlocal.adapter.crypto.Argon2idKdf",
                "com.codewitheli.vaultlocal.adapter.persistence.SqliteDatabase",
                "com.codewitheli.vaultlocal.adapter.ui.Navigator",
                "com.codewitheli.vaultlocal.app.Main")) {
            org.assertj.core.api.Assertions.assertThat(classes.contain(name)).as(name).isTrue();
        }
    }

    @Test
    void domainDependsOnlyOnTheJdk() {
        ArchRule rule = classes().that().resideInAPackage("..vaultlocal.domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("..vaultlocal.domain..", "java..");
        rule.check(classes);
    }

    @Test
    void applicationDoesNotDependOnAdaptersOrFrameworks() {
        ArchRule rule = noClasses().that().resideInAPackage("..vaultlocal.application..")
                .should().dependOnClassesThat().resideInAnyPackage("..vaultlocal.adapter..", "..vaultlocal.app..",
                        "javafx..", "org.sqlite..", "org.bouncycastle..", "com.sun.jna..");
        rule.check(classes);
    }

    @Test
    void adaptersDoNotDependOnEachOther() {
        noClasses().that().resideInAPackage("..adapter.crypto..")
                .should().dependOnClassesThat().resideInAnyPackage("..adapter.persistence..", "..adapter.ui..")
                .check(classes);
        noClasses().that().resideInAPackage("..adapter.persistence..")
                .should().dependOnClassesThat().resideInAnyPackage("..adapter.crypto..", "..adapter.ui..")
                .check(classes);
        noClasses().that().resideInAPackage("..adapter.ui..")
                .should().dependOnClassesThat().resideInAnyPackage("..adapter.crypto..", "..adapter.persistence..",
                        "org.sqlite..", "org.bouncycastle..")
                .check(classes);
    }

    @Test
    void persistenceNeverSeesPlaintextEntries() {
        noClasses().that().resideInAPackage("..adapter.persistence..")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.codewitheli.vaultlocal.domain.entry.Entry")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.codewitheli.vaultlocal.domain.secret.Secret")
                .check(classes);
    }

    @Test
    void noNetworkApisAnywhere() {
        ArchRule rule = noClasses().should().dependOnClassesThat().resideInAnyPackage("java.net.http..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.Socket")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.ServerSocket")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.DatagramSocket")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.URLConnection")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.HttpURLConnection")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.InetAddress")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.InetSocketAddress")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.Proxy")
                .orShould().dependOnClassesThat().resideInAnyPackage("java.nio.channels.spi..")
                .orShould().callMethod(java.net.URL.class, "openConnection")
                .orShould().callMethod(java.net.URL.class, "openStream")
                .orShould().dependOnClassesThat().resideInAnyPackage("javafx.scene.web..", "javafx.scene.media..")
                .because("VaultLocal is 100% local: no sockets, HTTP, WebView or media streaming");
        rule.check(classes);
    }

    @Test
    void javaNetIsOnlyUsedForUriSyntaxAndClasspathResources() {
        DescribedPredicate<JavaClass> disallowedJavaNet = JavaClass.Predicates.resideInAPackage("java.net")
                .and(DescribedPredicate.not(JavaClass.Predicates.type(java.net.URI.class)))
                .and(DescribedPredicate.not(JavaClass.Predicates.type(java.net.URISyntaxException.class)))
                .and(DescribedPredicate.not(JavaClass.Predicates.type(java.net.URL.class)));
        noClasses().should().dependOnClassesThat(disallowedJavaNet)
                .because("java.net may only be used for URI syntax checks and classpath resource URLs")
                .check(classes);
        noClasses().that().doNotHaveFullyQualifiedName("com.codewitheli.vaultlocal.domain.entry.EntryDetails")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.net.URI")
                .because("only the URL field's syntax check parses URIs; nothing ever opens one")
                .check(classes);
    }
}
