package com.manara.backend.privacy.service;

import com.manara.backend.privacy.model.LoadedPrivacyPolicy;
import com.manara.backend.privacy.model.PrivacyPolicyContent;
import com.manara.backend.privacy.model.PrivacyPolicyStatus;
import com.manara.backend.privacy.model.PrivacyPolicyVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules that keep the privacy policy honest, checked without a Spring context: only published
 * text is served, published text cannot be edited in place, and nothing malformed or half-drafted
 * gets past startup.
 */
class PrivacyPolicyRegistryTest {

    static final String PUBLISHED_FIXTURE = "legal/privacy/fixture-published-9.0.ar.json";
    static final String CANDIDATE_FIXTURE = "legal/privacy/fixture-candidate-9.1.ar.json";

    private final PrivacyPolicyContentLoader loader =
            new PrivacyPolicyContentLoader(new DefaultResourceLoader(), JsonMapper.builder().build());

    static String sha256Of(String resource) {
        try (InputStream in = PrivacyPolicyRegistryTest.class.getClassLoader().getResourceAsStream(resource)) {
            return PrivacyPolicyContentLoader.sha256(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static PrivacyPolicyVersion published(String resource, String id) {
        return new PrivacyPolicyVersion(id, "ar", resource, PrivacyPolicyStatus.PUBLISHED,
                LocalDate.of(2030, 1, 2), null, sha256Of(resource));
    }

    static PrivacyPolicyVersion candidate(String resource, String id) {
        return new PrivacyPolicyVersion(id, "ar", resource, PrivacyPolicyStatus.CANDIDATE, null, null, null);
    }

    @Test
    @DisplayName("the shipped catalogue loads, and its candidate 1.0 is not served")
    void shippedCatalogueLoadsAndServesNothingUnpublished() {
        PrivacyPolicyRegistry registry =
                new PrivacyPolicyRegistry(PrivacyPolicyCatalog.VERSIONS, PrivacyPolicyCatalog.CURRENT_ID, loader);

        LoadedPrivacyPolicy oneZero = registry.find("1.0").orElseThrow();
        assertThat(oneZero.version().status()).isEqualTo(PrivacyPolicyStatus.CANDIDATE);
        assertThat(registry.current()).isEmpty();
    }

    @Test
    @DisplayName("a published current version is served with its own metadata")
    void publishedCurrentVersionIsServed() {
        PrivacyPolicyRegistry registry =
                new PrivacyPolicyRegistry(List.of(published(PUBLISHED_FIXTURE, "9.0")), "9.0", loader);

        LoadedPrivacyPolicy current = registry.current().orElseThrow();
        assertThat(current.version().id()).isEqualTo("9.0");
        assertThat(current.version().effectiveDate()).isEqualTo(LocalDate.of(2030, 1, 2));
        assertThat(current.content().sections()).extracting(PrivacyPolicyContent.Section::id)
                .containsExactly("first", "contact");
    }

    @Test
    @DisplayName("a pointer at a candidate serves nothing, even with an older published version present")
    void candidatePointerNeverFallsBack() {
        PrivacyPolicyVersion old = published(PUBLISHED_FIXTURE, "9.0");
        PrivacyPolicyVersion next = new PrivacyPolicyVersion("9.1", "ar", CANDIDATE_FIXTURE,
                PrivacyPolicyStatus.CANDIDATE, null, "9.0", null);

        PrivacyPolicyRegistry registry = new PrivacyPolicyRegistry(List.of(old, next), "9.1", loader);

        assertThat(registry.current()).isEmpty();
    }

    @Test
    @DisplayName("editing a published text in place stops startup")
    void publishedTextIsFrozen() {
        PrivacyPolicyVersion tampered = new PrivacyPolicyVersion("9.0", "ar", PUBLISHED_FIXTURE,
                PrivacyPolicyStatus.PUBLISHED, LocalDate.of(2030, 1, 2), null, "0".repeat(64));

        assertThatThrownBy(() -> new PrivacyPolicyRegistry(List.of(tampered), "9.0", loader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed after publication");
    }

    @Test
    @DisplayName("a published version needs an effective date and a pinned hash")
    void publicationMetadataIsRequired() {
        PrivacyPolicyVersion noDate = new PrivacyPolicyVersion("9.0", "ar", PUBLISHED_FIXTURE,
                PrivacyPolicyStatus.PUBLISHED, null, null, sha256Of(PUBLISHED_FIXTURE));
        PrivacyPolicyVersion noHash = new PrivacyPolicyVersion("9.0", "ar", PUBLISHED_FIXTURE,
                PrivacyPolicyStatus.PUBLISHED, LocalDate.of(2030, 1, 2), null, null);

        assertThatThrownBy(() -> new PrivacyPolicyRegistry(List.of(noDate), "9.0", loader))
                .hasMessageContaining("effective date");
        assertThatThrownBy(() -> new PrivacyPolicyRegistry(List.of(noHash), "9.0", loader))
                .hasMessageContaining("hash pinned");
    }

    @Test
    @DisplayName("a candidate may not claim an effective date")
    void candidateHasNoEffectiveDate() {
        PrivacyPolicyVersion dated = new PrivacyPolicyVersion("9.1", "ar", CANDIDATE_FIXTURE,
                PrivacyPolicyStatus.CANDIDATE, LocalDate.of(2030, 1, 2), null, null);

        assertThatThrownBy(() -> new PrivacyPolicyRegistry(List.of(dated), "9.1", loader))
                .hasMessageContaining("candidate has no effective date");
    }

    @Test
    @DisplayName("the catalogue id must match the version written in the file")
    void idMustMatchContent() {
        assertThatThrownBy(() -> new PrivacyPolicyRegistry(List.of(candidate(CANDIDATE_FIXTURE, "7.7")), "7.7", loader))
                .hasMessageContaining("does not match catalogue id");
    }

    @Test
    @DisplayName("a current pointer at a version the catalogue lacks stops startup")
    void unknownCurrentIdIsRefused() {
        assertThatThrownBy(() -> new PrivacyPolicyRegistry(List.of(candidate(CANDIDATE_FIXTURE, "9.1")), "4.2", loader))
                .hasMessageContaining("not in the catalogue");
    }

    @Test
    @DisplayName("a version cannot supersede one that does not exist")
    void supersedesMustResolve() {
        PrivacyPolicyVersion orphan = new PrivacyPolicyVersion("9.1", "ar", CANDIDATE_FIXTURE,
                PrivacyPolicyStatus.CANDIDATE, null, "8.0", null);

        assertThatThrownBy(() -> new PrivacyPolicyRegistry(List.of(orphan), "9.1", loader))
                .hasMessageContaining("supersedes unknown version");
    }

    @Test
    @DisplayName("drafting notes, misplaced fields and a drifting contact address are all refused")
    void editorialChecks() {
        PrivacyPolicyContent good = loader.load(PUBLISHED_FIXTURE).content();
        PrivacyPolicyVersion version = candidate(PUBLISHED_FIXTURE, "9.0");

        PrivacyPolicyContent residue = withFirstParagraph(good, "قيد التحديد: يُستكمل لاحقًا");
        assertThat(PrivacyPolicyContentValidator.problems(version, residue, "x"))
                .anyMatch(p -> p.contains("drafting residue"));

        PrivacyPolicyContent driftingEmail = new PrivacyPolicyContent(good.policyId(), good.version(), good.language(),
                good.title(), good.summary(),
                new PrivacyPolicyContent.Operator("Fixture Operator", "Fixture Owner", "someone-else@example.com"),
                good.sections());
        assertThat(PrivacyPolicyContentValidator.problems(version, driftingEmail, "x"))
                .anyMatch(p -> p.contains("operator.privacyEmail exactly"));

        PrivacyPolicyContent badType = new PrivacyPolicyContent(good.policyId(), good.version(), good.language(),
                good.title(), good.summary(), good.operator(),
                List.of(new PrivacyPolicyContent.Section("first", "1", "t",
                        List.of(new PrivacyPolicyContent.Block("html", "<b>x</b>", null, null, null)))));
        assertThat(PrivacyPolicyContentValidator.problems(version, badType, "x"))
                .anyMatch(p -> p.contains("unknown block type"));

        PrivacyPolicyContent duplicateIds = new PrivacyPolicyContent(good.policyId(), good.version(), good.language(),
                good.title(), good.summary(), good.operator(),
                List.of(good.sections().get(0), good.sections().get(0)));
        assertThat(PrivacyPolicyContentValidator.problems(version, duplicateIds, "x"))
                .anyMatch(p -> p.contains("duplicate id"));
    }

    @Test
    @DisplayName("an unknown property in a content file is an error, not silently dropped")
    void unknownPropertiesAreRefused() {
        assertThatThrownBy(() -> loader.load("legal/privacy/fixture-misspelt.ar.json"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("the 1.0 text keeps the owner's facts exactly and does not call the operator a company")
    void shippedOneZeroCarriesTheApprovedFacts() {
        PrivacyPolicyContent text = loader.load("legal/privacy/privacy-policy-1.0.ar.json").content();
        String all = allText(text);

        assertThat(text.operator().name()).isEqualTo("Manara EDU");
        assertThat(text.operator().owner()).isEqualTo("Hamed Mohamed");
        // The owner's spelling, deliberately preserved.
        assertThat(text.operator().privacyEmail()).isEqualTo("privecy@manara-edu.com");
        assertThat(all).doesNotContain("شركة").doesNotContain("مسجّلة تجاريًا").doesNotContain("GDPR");
        // Code validity and record retention are two different numbers and both are stated.
        assertThat(all).contains("10 دقائق").contains("بعد 7 أيام من إنشائها");
        // Not processing payments today; Kashier is not integrated.
        assertThat(all).doesNotContain("كاشير").doesNotContain("Kashier");
        assertThat(text.sections()).extracting(PrivacyPolicyContent.Section::id).containsExactly(
                "operator", "data-we-collect", "purposes", "sign-in-and-sessions", "learning-records",
                "cookies-and-embeds", "providers-and-locations", "retention", "deletion-and-backups",
                "your-rights", "contact", "updates");
    }

    private static PrivacyPolicyContent withFirstParagraph(PrivacyPolicyContent content, String text) {
        PrivacyPolicyContent.Section first = content.sections().get(0);
        PrivacyPolicyContent.Section replaced = new PrivacyPolicyContent.Section(first.id(), first.number(), first.title(),
                List.of(new PrivacyPolicyContent.Block("paragraph", text, null, null, null)));
        return new PrivacyPolicyContent(content.policyId(), content.version(), content.language(), content.title(),
                content.summary(), content.operator(), List.of(replaced, content.sections().get(1)));
    }

    private static String allText(PrivacyPolicyContent content) {
        return content.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .map(block -> String.join(" ",
                        block.text() == null ? "" : block.text(),
                        block.items() == null ? "" : String.join(" ", block.items())))
                .collect(Collectors.joining(" ", content.title() + " " + content.summary() + " ", ""));
    }
}
