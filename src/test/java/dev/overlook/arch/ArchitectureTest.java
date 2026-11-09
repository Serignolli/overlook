package dev.overlook.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Garantias de privacidade verificadas no bytecode. */
class ArchitectureTest {

    private static JavaClasses classes;

    static final ArchRule ONLY_VERCEL_PACKAGE_STARTS_PROCESSES = noClasses()
            .that().resideOutsideOfPackage("dev.overlook.vercel..")
            .should().dependOnClassesThat().haveFullyQualifiedName(ProcessBuilder.class.getName())
            .orShould().callMethod(Runtime.class, "exec", String.class)
            .orShould().callMethod(Runtime.class, "exec", String[].class)
            .because("só o gateway da Vercel executa a CLI");

    static final ArchRule NO_OUTBOUND_HTTP_CLIENTS = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.net.http..",
                    "org.eclipse.jetty.client..",
                    "okhttp3..",
                    "org.apache.http..",
                    "org.apache.hc..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.HttpURLConnection")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.URLConnection")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.Socket")
            .orShould().callMethod(java.net.URL.class, "openConnection")
            .orShould().callMethod(java.net.URL.class, "openStream")
            .because("o app não abre conexões de saída; só a CLI fala com a Vercel");

    static final ArchRule ONLY_GATEWAY_KNOWS_THE_CLI = noClasses()
            .that().resideOutsideOfPackage("dev.overlook.vercel..")
            .and().doNotHaveFullyQualifiedName("dev.overlook.App")
            .should().dependOnClassesThat().haveFullyQualifiedName("dev.overlook.vercel.CliRunner")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("dev.overlook.vercel.CliVercelGateway")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("dev.overlook.vercel.CliLocator")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("dev.overlook.vercel.CliErrorClassifier")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("dev.overlook.vercel.CliResult")
            .because("trocar CLI por API oficial deve mexer só no pacote vercel");

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.overlook");
    }

    @Test
    void importedProductionCode() {
        assertThat(classes.contain("dev.overlook.App")).isTrue();
        assertThat(classes.contain("dev.overlook.vercel.CliRunner")).isTrue();
    }

    @Test
    void onlyVercelPackageStartsProcesses() {
        ONLY_VERCEL_PACKAGE_STARTS_PROCESSES.check(classes);
    }

    @Test
    void noOutboundHttpClients() {
        NO_OUTBOUND_HTTP_CLIENTS.check(classes);
    }

    @Test
    void onlyGatewayKnowsTheCli() {
        ONLY_GATEWAY_KNOWS_THE_CLI.check(classes);
    }

    @Test
    void rulesActuallyDetectViolations() {
        // o próprio teste usa Socket (via RawHttp): a regra precisa acusar quando ele entra na análise
        JavaClasses withTests = new ClassFileImporter().importPackages("dev.overlook.web");
        assertThatThrownBy(() -> NO_OUTBOUND_HTTP_CLIENTS.check(withTests)).isInstanceOf(AssertionError.class);
    }
}
