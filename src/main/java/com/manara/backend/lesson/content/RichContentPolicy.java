package com.manara.backend.lesson.content;

import java.util.Set;

/**
 * The whole of what a lesson document is allowed to contain.
 *
 * <p>Every allowlist the sanitizer enforces is here, in one file, because the alternative — a set
 * of literals spread across a parser — is how the editor and the validator end up disagreeing about
 * which colours exist. The editor's toolbar is built from the same vocabulary, so a control the
 * instructor can see is a value the server accepts, by construction rather than by review.
 *
 * <h2>Tokens, never CSS</h2>
 * Nothing here is a colour, a pixel count or a CSS declaration. {@code MUTED} is a name; what it
 * renders as is the design system's decision at paint time, on the surface doing the painting. That
 * is what keeps instructor styling from having any way to reach the page around it: there is no
 * value an author can choose that becomes a stylesheet, so there is nothing to escape from.
 *
 * <p>It is also what makes light and dark both work without the author thinking about either, and
 * what guarantees contrast — a palette of seven names can be checked once, whereas an open colour
 * picker produces yellow-on-white that nobody sees until a learner cannot read the lesson.
 *
 * <h2>Direction-neutral by construction</h2>
 * Alignment is {@code START} / {@code CENTER} / {@code END}, never left and right. An Arabic lesson
 * and an English one are authored with the same three controls and each resolves the way its own
 * direction reads. There is deliberately no way to express "left" — a document that could would
 * render one of the two languages backwards.
 */
public final class RichContentPolicy {

    private RichContentPolicy() {
    }

    /** Schema version written into every stored document. */
    public static final int VERSION = 1;

    // ── Structure ────────────────────────────────────────────────────────────

    public static final String BLOCK_PARAGRAPH = "paragraph";
    public static final String BLOCK_HEADING = "heading";
    public static final String BLOCK_BULLET_LIST = "bulletList";
    public static final String BLOCK_ORDERED_LIST = "orderedList";
    public static final String BLOCK_QUOTE = "quote";
    public static final String BLOCK_DIVIDER = "divider";
    public static final String BLOCK_CTA = "cta";

    public static final Set<String> BLOCK_TYPES = Set.of(
            BLOCK_PARAGRAPH, BLOCK_HEADING, BLOCK_BULLET_LIST, BLOCK_ORDERED_LIST,
            BLOCK_QUOTE, BLOCK_DIVIDER, BLOCK_CTA);

    /** H1, H2 and H3. Deeper levels are refused: a lesson body is not a specification. */
    public static final Set<Integer> HEADING_LEVELS = Set.of(1, 2, 3);

    // ── Inline ───────────────────────────────────────────────────────────────

    public static final String INLINE_TEXT = "text";
    public static final String INLINE_BREAK = "break";

    public static final Set<String> INLINE_TYPES = Set.of(INLINE_TEXT, INLINE_BREAK);

    public static final String MARK_BOLD = "bold";
    public static final String MARK_ITALIC = "italic";
    public static final String MARK_UNDERLINE = "underline";
    public static final String MARK_STRIKE = "strike";
    public static final String MARK_COLOR = "color";
    public static final String MARK_LINK = "link";

    /** Marks carrying nothing but their own presence. */
    public static final Set<String> FLAG_MARKS = Set.of(
            MARK_BOLD, MARK_ITALIC, MARK_UNDERLINE, MARK_STRIKE);

    public static final Set<String> MARK_TYPES = Set.of(
            MARK_BOLD, MARK_ITALIC, MARK_UNDERLINE, MARK_STRIKE, MARK_COLOR, MARK_LINK);

    // ── Design tokens ────────────────────────────────────────────────────────

    /** Never left/right — see the class note on direction. */
    public static final Set<String> ALIGNMENTS = Set.of("START", "CENTER", "END");
    public static final String DEFAULT_ALIGNMENT = "START";

    /** Line spacing. */
    public static final Set<String> LEADINGS = Set.of("TIGHT", "NORMAL", "RELAXED", "LOOSE");
    public static final String DEFAULT_LEADING = "NORMAL";

    /** Space below a block. */
    public static final Set<String> SPACINGS = Set.of("COMPACT", "NORMAL", "ROOMY");
    public static final String DEFAULT_SPACING = "NORMAL";

    /** Body text size. Headings take their size from their level instead. */
    public static final Set<String> TEXT_SIZES = Set.of("SMALL", "NORMAL", "LARGE");
    public static final String DEFAULT_TEXT_SIZE = "NORMAL";

    /**
     * The palette, by role rather than by hue.
     *
     * <p>Roles, not colours, so the same document is readable in a light theme and a dark one and
     * an instructor never picks a value that disappears against a background they did not see.
     */
    public static final Set<String> TEXT_COLORS = Set.of(
            "DEFAULT", "MUTED", "PRIMARY", "ACCENT", "SUCCESS", "WARNING", "DANGER");
    public static final String DEFAULT_TEXT_COLOR = "DEFAULT";

    /** Call-to-action styles, matching the button variants the design system already ships. */
    public static final Set<String> CTA_VARIANTS = Set.of("PRIMARY", "SECONDARY", "OUTLINE", "TEXT");
    public static final String DEFAULT_CTA_VARIANT = "PRIMARY";

    // ── Limits ───────────────────────────────────────────────────────────────
    //
    // A lesson document is bounded in every dimension it has. The schema is fixed-depth by design —
    // a list holds items, an item holds inline text, and nothing nests further — so the recursive
    // blow-up that costs HTML sanitizers their stack is not expressible here at all. What is left
    // to bound is breadth and length, which these do.

    public static final int MAX_DOCUMENT_BYTES = 256 * 1024;
    public static final int MAX_BLOCKS = 500;
    public static final int MAX_LIST_ITEMS = 200;
    public static final int MAX_INLINES_PER_BLOCK = 400;
    public static final int MAX_TEXT_LENGTH = 20_000;
    public static final int MAX_CTA_LABEL_LENGTH = 120;
}
