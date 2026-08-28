package com.manara.backend.lesson.content;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.lesson.LessonContentFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The security boundary, exercised directly.
 *
 * <p>These are the tests that matter most in this feature. Everything else can regress into a bug;
 * this can regress into stored cross-site scripting on a page every enrolled learner opens.
 *
 * <p>The sanitizer's design is what makes them read the way they do. It rebuilds a document from an
 * allowlist rather than filtering a hostile one, so most of these are not "is this payload removed"
 * but "is this payload simply never copied" — which is why a {@code <script>} tag survives as
 * literal text rather than being stripped. Text is safe; the renderer prints text as text.
 */
class RichContentSanitizerTest {

    private final RichContentSanitizer sanitizer = LessonContentFixtures.sanitizer();

    // --- what is kept ---------------------------------------------------------

    @Test
    @DisplayName("keeps prose, structure and the tokens the policy allows")
    void keepsAllowedContent() {
        String result = sanitizer.sanitize("""
                {"blocks":[
                  {"type":"heading","level":1,"align":"CENTER","content":[{"type":"text","text":"ما هو التفكير النقدي؟"}]},
                  {"type":"paragraph","size":"LARGE","leading":"RELAXED","content":[
                    {"type":"text","text":"مقدمة ","marks":[{"type":"bold"}]},
                    {"type":"text","text":"important","marks":[{"type":"italic"},{"type":"color","value":"PRIMARY"}]}
                  ]},
                  {"type":"bulletList","items":[{"content":[{"type":"text","text":"تحليل المعلومات"}]}]},
                  {"type":"quote","content":[{"type":"text","text":"اقتباس"}]},
                  {"type":"divider"},
                  {"type":"cta","label":"ابدأ التمرين","href":"https://example.com/exercise","variant":"OUTLINE","align":"CENTER"}
                ]}""");

        assertThat(result)
                .contains("\"version\":1")
                .contains("ما هو التفكير النقدي؟")
                .contains("\"level\":1")
                .contains("\"align\":\"CENTER\"")
                .contains("\"size\":\"LARGE\"")
                .contains("\"leading\":\"RELAXED\"")
                .contains("\"type\":\"bold\"")
                .contains("\"value\":\"PRIMARY\"")
                .contains("\"type\":\"bulletList\"")
                .contains("\"type\":\"quote\"")
                .contains("\"type\":\"divider\"")
                .contains("ابدأ التمرين")
                .contains("\"variant\":\"OUTLINE\"");
    }

    @Test
    @DisplayName("keeps mixed Arabic and English, numbers and punctuation exactly as written")
    void keepsMixedDirectionText() {
        String text = "التفكير النقدي (Critical Thinking) — 3 خطوات: analyse, evaluate, decide.";
        String result = sanitizer.sanitize(
                """
                {"blocks":[{"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}"""
                        .formatted(text));

        assertThat(result).contains(text);
    }

    @Test
    @DisplayName("keeps a link on the four allowed schemes")
    void keepsAllowedLinkSchemes() {
        for (String url : new String[]{
                "https://example.com/a", "http://example.com/b",
                "mailto:tutor@example.com", "tel:+201234567890"}) {
            String result = sanitizer.sanitize("""
                    {"blocks":[{"type":"paragraph","content":[
                      {"type":"text","text":"link","marks":[{"type":"link","href":"%s"}]}]}]}"""
                    .formatted(url));
            assertThat(result).as("scheme of %s", url).contains(url);
        }
    }

    // --- what cannot get through ---------------------------------------------

    @Test
    @DisplayName("a script payload survives only as the literal characters, never as a node")
    void scriptPayloadBecomesText() {
        String result = sanitizer.sanitize("""
                {"blocks":[{"type":"paragraph","content":[
                  {"type":"text","text":"<script>alert(1)</script>"}]}]}""");

        // Present as text — which is exactly right. The renderer builds React elements from tagged
        // records and never interprets a string as markup, so these characters are printed, not run.
        assertThat(result).contains("alert(1)");
        // And there is no structure anywhere that could carry it as anything else.
        assertThat(result).doesNotContain("\"html\"").doesNotContain("\"raw\"");
    }

    @ParameterizedTest(name = "an unknown node type ({0}) is not copied")
    @ValueSource(strings = {"script", "iframe", "object", "embed", "img", "style", "table"})
    void dropsUnknownBlockTypes(String type) {
        assertThatThrownBy(() -> sanitizer.sanitize("""
                {"blocks":[{"type":"%s","src":"https://evil.example/x"}]}""".formatted(type)))
                // Nothing recognisable survived, so the document has nothing to learn from — which
                // is the emptiness rule doing the refusing, not a filter that knew about iframes.
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.richContent.empty");
    }

    @Test
    @DisplayName("event handlers and unknown attributes are never copied onto a block")
    void dropsUnknownAttributes() {
        String result = sanitizer.sanitize("""
                {"blocks":[{"type":"paragraph","onclick":"steal()","onerror":"steal()",
                  "style":"position:fixed;top:0","class":"admin-panel",
                  "content":[{"type":"text","text":"ordinary text","onmouseover":"steal()"}]}]}""");

        assertThat(result)
                .contains("ordinary text")
                .doesNotContain("onclick")
                .doesNotContain("onerror")
                .doesNotContain("onmouseover")
                .doesNotContain("steal")
                .doesNotContain("position:fixed")
                .doesNotContain("admin-panel");
    }

    @ParameterizedTest(name = "a {0} link is refused rather than stored")
    @ValueSource(strings = {
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "vbscript:msgbox(1)",
            "blob:https://example.com/x",
            "file:///etc/passwd"})
    void refusesUnsafeLinkSchemes(String url) {
        assertThatThrownBy(() -> sanitizer.sanitize("""
                {"blocks":[{"type":"paragraph","content":[
                  {"type":"text","text":"click","marks":[{"type":"link","href":"%s"}]}]}]}"""
                .formatted(url)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.richContent.linkSchemeUnsupported");
    }

    @Test
    @DisplayName("a scheme hidden behind control characters is still read as the scheme it is")
    void refusesObfuscatedJavascriptScheme() {
        assertThatThrownBy(() -> sanitizer.sanitize("""
                {"blocks":[{"type":"paragraph","content":[
                  {"type":"text","text":"click","marks":[{"type":"link","href":"java\\u0009script:alert(1)"}]}]}]}"""))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a CTA pointing at javascript: is refused, and says it is the button")
    void refusesUnsafeCtaDestination() {
        assertThatThrownBy(() -> sanitizer.sanitize("""
                {"blocks":[{"type":"cta","label":"Click","href":"javascript:alert(1)"}]}"""))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.richContent.ctaHrefUnsupported");
    }

    @Test
    @DisplayName("a colour outside the palette falls back rather than being stored")
    void refusesArbitraryColour() {
        String result = sanitizer.sanitize("""
                {"blocks":[{"type":"paragraph","content":[
                  {"type":"text","text":"x","marks":[{"type":"color","value":"#FFFFFF"}]}]}]}""");

        // Falls back to DEFAULT, which is dropped as a mark entirely — so a hostile or mistaken
        // colour cannot produce invisible text.
        assertThat(result).doesNotContain("#FFFFFF").doesNotContain("\"type\":\"color\"");
    }

    // --- emptiness ------------------------------------------------------------

    @ParameterizedTest(name = "refuses a document that is {0}")
    @ValueSource(strings = {
            "{\"blocks\":[]}",
            "{\"blocks\":[{\"type\":\"paragraph\",\"content\":[]}]}",
            "{\"blocks\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"   \"}]}]}",
            "{\"blocks\":[{\"type\":\"heading\",\"level\":2,\"content\":[{\"type\":\"text\",\"text\":\" \"}]}]}",
            "{\"blocks\":[{\"type\":\"bulletList\",\"items\":[]}]}",
            "{\"blocks\":[{\"type\":\"divider\"},{\"type\":\"divider\"}]}"})
    void refusesEmptyDocuments(String json) {
        assertThatThrownBy(() -> sanitizer.sanitize(json))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.richContent.empty");
    }

    @Test
    @DisplayName("a lesson that is one call-to-action is thin, but it is not empty")
    void acceptsACtaOnlyDocument() {
        assertThatCode(() -> sanitizer.sanitize("""
                {"blocks":[{"type":"cta","label":"ابدأ التمرين","href":"https://example.com"}]}"""))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "refuses a payload that is {0}")
    @ValueSource(strings = {"", "   ", "not json at all", "[]", "\"a string\"", "123"})
    void refusesUnusablePayloads(String json) {
        assertThatThrownBy(() -> sanitizer.sanitize(json)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("refuses a document over the size limit before parsing it")
    void refusesOversizedDocuments() {
        String huge = "x".repeat(RichContentPolicy.MAX_DOCUMENT_BYTES + 1);
        assertThatThrownBy(() -> sanitizer.sanitize(huge))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.richContent.tooLarge");
    }

    // --- canonical output -----------------------------------------------------

    @Test
    @DisplayName("the same document always produces the same bytes, whatever order it arrives in")
    void outputIsCanonical() {
        String once = sanitizer.sanitize("""
                {"blocks":[{"type":"paragraph","align":"START","size":"NORMAL",
                  "content":[{"type":"text","text":"نص","marks":[
                    {"type":"link","href":"https://example.com"},{"type":"bold"}]}]}]}""");

        String again = sanitizer.sanitize("""
                {"version":99,"blocks":[{"size":"NORMAL","type":"paragraph","align":"START",
                  "content":[{"marks":[{"type":"bold"},{"type":"link","href":"https://example.com"}],
                    "type":"text","text":"نص"}]}]}""");

        // Same content, different key order, different mark order, a version the client invented.
        // Identical bytes — which is what stops an unchanged save from announcing a new version of
        // the course to everyone enrolled in it.
        assertThat(again).isEqualTo(once);
        assertThat(again).contains("\"version\":1");
    }

    @Test
    @DisplayName("re-sanitizing stored output changes nothing")
    void sanitizingIsIdempotent() {
        String stored = sanitizer.sanitize(LessonContentFixtures.document("محتوى الدرس"));
        assertThat(sanitizer.sanitize(stored)).isEqualTo(stored);
    }

    @Test
    @DisplayName("a duplicated mark is collapsed, so two equal documents compare equal")
    void collapsesDuplicateMarks() {
        String result = sanitizer.sanitize("""
                {"blocks":[{"type":"paragraph","content":[
                  {"type":"text","text":"x","marks":[{"type":"bold"},{"type":"bold"}]}]}]}""");

        assertThat(result.split("\"type\":\"bold\"", -1).length - 1).isEqualTo(1);
    }
}
