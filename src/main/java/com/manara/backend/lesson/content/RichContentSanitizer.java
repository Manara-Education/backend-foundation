package com.manara.backend.lesson.content;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.manara.backend.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Turns whatever a client sent into the only shape a lesson document is ever stored in.
 *
 * <h2>Why this is a rebuild rather than a scrub</h2>
 * Nothing from the request survives into the database by default. The sanitizer walks the submitted
 * tree and <em>constructs a new one</em> from the values it recognises, so an unknown field, an
 * unexpected attribute or an entire node type Manara has never heard of is not removed — it is
 * simply never copied. That is a materially stronger position than filtering: a filter has to
 * anticipate every dangerous thing, and this has to anticipate every safe one. The safe set is
 * closed, written down in {@link RichContentPolicy}, and about forty values long.
 *
 * <p>It is also why {@code <script>}, {@code onerror=}, {@code <iframe>} and their relatives need no
 * special handling and get none. There is no node type that carries markup and no attribute that
 * carries a handler, so a payload containing them produces a document containing text — the
 * characters are stored as the literal characters they are, and the renderer prints them as text
 * because it prints everything as text. The injection has nowhere to land.
 *
 * <h2>Deterministic output</h2>
 * Fields are written in a fixed order and defaults are made explicit, so the same document always
 * serialises to the same bytes. That is what lets update tracking compare two versions with
 * {@code equals} and be right: an instructor who opens a lesson and saves it unchanged does not
 * announce a new version of the course to everyone enrolled in it.
 *
 * <h2>What it refuses outright</h2>
 * Two things, both because silence would be worse than an error. An unsafe URL is rejected rather
 * than dropped — an author who typed one needs to hear about it, and a link that vanishes on save
 * looks like a bug. And a document with nothing to learn from is rejected, so "formatting with no
 * content" cannot be published as a lesson.
 *
 * @see LinkUrlPolicy for the URL rule itself
 */
@Component
@RequiredArgsConstructor
public class RichContentSanitizer {

    /**
     * This class's own mapper, deliberately not the application's.
     *
     * <p>Injecting the shared one would make the bytes written to {@code lessons.rich_content}
     * depend on however Jackson happens to be configured for the HTTP layer — a naming strategy, an
     * inclusion rule, a module someone adds next year. Canonical output has to be a property of
     * this class alone, because update tracking compares those bytes: a global Jackson change that
     * altered them would tell every enrolled learner on the platform that every rich-content lesson
     * had been updated, the first time each was saved.
     *
     * <p>It is only ever used to read a tree and to build one, so it needs no configuration at all.
     * Thread-safe once constructed, which is what lets it be static.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LinkUrlPolicy linkUrlPolicy;

    /**
     * Validates, normalises and re-serialises a submitted document.
     *
     * @param rawJson the client's document, as JSON text
     * @return canonical JSON, ready to store and safe to compare
     * @throws BusinessException when the payload is not parseable, is over the size limit, carries
     *                           an unsafe URL or an unusable call-to-action, or contains nothing a
     *                           learner could read
     */
    public String sanitize(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new BusinessException("error.richContent.required");
        }
        if (rawJson.getBytes(StandardCharsets.UTF_8).length > RichContentPolicy.MAX_DOCUMENT_BYTES) {
            throw new BusinessException("error.richContent.tooLarge");
        }

        JsonNode submitted;
        try {
            submitted = JSON.readTree(rawJson);
        } catch (JsonProcessingException e) {
            throw new BusinessException("error.richContent.malformed");
        }
        if (submitted == null || !submitted.isObject()) {
            throw new BusinessException("error.richContent.malformed");
        }

        ArrayNode blocks = JSON.createArrayNode();
        JsonNode submittedBlocks = submitted.get("blocks");
        if (submittedBlocks != null && submittedBlocks.isArray()) {
            for (JsonNode block : submittedBlocks) {
                if (blocks.size() >= RichContentPolicy.MAX_BLOCKS) {
                    break;
                }
                ObjectNode cleaned = sanitizeBlock(block);
                if (cleaned != null) {
                    blocks.add(cleaned);
                }
            }
        }

        if (!hasSomethingToRead(blocks)) {
            throw new BusinessException("error.richContent.empty");
        }

        // The version is the server's to state. A client claiming a version it invented would
        // otherwise decide how its own document is read back.
        ObjectNode document = JSON.createObjectNode();
        document.put("version", RichContentPolicy.VERSION);
        document.set("blocks", blocks);

        return document.toString();
    }

    /** {@link #sanitize(String)} for an optional field: null and blank stay null. */
    public String sanitizeIfPresent(String rawJson) {
        return rawJson == null || rawJson.isBlank() ? null : sanitize(rawJson);
    }

    // ── Blocks ───────────────────────────────────────────────────────────────

    /** @return the rebuilt block, or null when it is of no recognised type or holds nothing */
    private ObjectNode sanitizeBlock(JsonNode block) {
        if (block == null || !block.isObject()) {
            return null;
        }
        String type = text(block, "type");
        if (type == null || !RichContentPolicy.BLOCK_TYPES.contains(type)) {
            return null;
        }

        return switch (type) {
            case RichContentPolicy.BLOCK_PARAGRAPH -> paragraph(block);
            case RichContentPolicy.BLOCK_HEADING -> heading(block);
            case RichContentPolicy.BLOCK_BULLET_LIST,
                 RichContentPolicy.BLOCK_ORDERED_LIST -> list(block, type);
            case RichContentPolicy.BLOCK_QUOTE -> quote(block);
            case RichContentPolicy.BLOCK_DIVIDER -> divider();
            case RichContentPolicy.BLOCK_CTA -> cta(block);
            default -> null;
        };
    }

    private ObjectNode paragraph(JsonNode block) {
        ArrayNode content = sanitizeInlines(block.get("content"));
        // An empty paragraph is dropped rather than stored. "<p></p>" repeated forty times is the
        // shape an empty lesson actually arrives in, and dropping it here is what lets the
        // emptiness check downstream be a simple one.
        if (content.isEmpty()) {
            return null;
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("type", RichContentPolicy.BLOCK_PARAGRAPH);
        node.put("align", token(block, "align", RichContentPolicy.ALIGNMENTS,
                RichContentPolicy.DEFAULT_ALIGNMENT));
        node.put("size", token(block, "size", RichContentPolicy.TEXT_SIZES,
                RichContentPolicy.DEFAULT_TEXT_SIZE));
        node.put("leading", token(block, "leading", RichContentPolicy.LEADINGS,
                RichContentPolicy.DEFAULT_LEADING));
        node.put("spacing", token(block, "spacing", RichContentPolicy.SPACINGS,
                RichContentPolicy.DEFAULT_SPACING));
        node.set("content", content);
        return node;
    }

    private ObjectNode heading(JsonNode block) {
        ArrayNode content = sanitizeInlines(block.get("content"));
        if (content.isEmpty()) {
            return null;
        }
        JsonNode level = block.get("level");
        int resolved = level != null && level.isInt() && RichContentPolicy.HEADING_LEVELS.contains(level.asInt())
                ? level.asInt()
                : 2;

        ObjectNode node = JSON.createObjectNode();
        node.put("type", RichContentPolicy.BLOCK_HEADING);
        node.put("level", resolved);
        node.put("align", token(block, "align", RichContentPolicy.ALIGNMENTS,
                RichContentPolicy.DEFAULT_ALIGNMENT));
        node.put("spacing", token(block, "spacing", RichContentPolicy.SPACINGS,
                RichContentPolicy.DEFAULT_SPACING));
        node.set("content", content);
        return node;
    }

    private ObjectNode list(JsonNode block, String type) {
        ArrayNode items = JSON.createArrayNode();
        JsonNode submittedItems = block.get("items");
        if (submittedItems != null && submittedItems.isArray()) {
            for (JsonNode item : submittedItems) {
                if (items.size() >= RichContentPolicy.MAX_LIST_ITEMS) {
                    break;
                }
                ArrayNode content = sanitizeInlines(item == null ? null : item.get("content"));
                if (content.isEmpty()) {
                    continue;
                }
                ObjectNode cleaned = JSON.createObjectNode();
                cleaned.set("content", content);
                items.add(cleaned);
            }
        }
        if (items.isEmpty()) {
            return null;
        }

        ObjectNode node = JSON.createObjectNode();
        node.put("type", type);
        node.put("align", token(block, "align", RichContentPolicy.ALIGNMENTS,
                RichContentPolicy.DEFAULT_ALIGNMENT));
        node.put("spacing", token(block, "spacing", RichContentPolicy.SPACINGS,
                RichContentPolicy.DEFAULT_SPACING));
        node.set("items", items);
        return node;
    }

    private ObjectNode quote(JsonNode block) {
        ArrayNode content = sanitizeInlines(block.get("content"));
        if (content.isEmpty()) {
            return null;
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("type", RichContentPolicy.BLOCK_QUOTE);
        node.put("align", token(block, "align", RichContentPolicy.ALIGNMENTS,
                RichContentPolicy.DEFAULT_ALIGNMENT));
        node.put("spacing", token(block, "spacing", RichContentPolicy.SPACINGS,
                RichContentPolicy.DEFAULT_SPACING));
        node.set("content", content);
        return node;
    }

    private ObjectNode divider() {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", RichContentPolicy.BLOCK_DIVIDER);
        return node;
    }

    /**
     * A call-to-action: a label, somewhere to go, and two design tokens.
     *
     * <p>Four fields, and there is deliberately no fifth. No handler, no target, no class, no
     * attribute bag — so an instructor's button cannot be made to do anything except navigate, and
     * cannot be made to look like Manara's own completion control by carrying its behaviour.
     *
     * <p>Refused rather than dropped when unusable: a button with no label is invisible and a button
     * with no destination is a dead end, and both are mistakes the author has to be told about.
     */
    private ObjectNode cta(JsonNode block) {
        String label = text(block, "label");
        if (label == null || label.isBlank()) {
            throw new BusinessException("error.richContent.ctaLabelRequired");
        }
        label = label.trim();
        if (label.length() > RichContentPolicy.MAX_CTA_LABEL_LENGTH) {
            throw new BusinessException("error.richContent.ctaLabelTooLong",
                    RichContentPolicy.MAX_CTA_LABEL_LENGTH);
        }

        String href;
        try {
            href = linkUrlPolicy.requireSafe(text(block, "href"));
        } catch (BusinessException e) {
            // Re-scoped so the instructor is told which control is wrong. A lesson can hold a dozen
            // links and one button, and "the link is not supported" sends them hunting.
            throw new BusinessException(ctaScoped(e.getMessageCode()));
        }

        ObjectNode node = JSON.createObjectNode();
        node.put("type", RichContentPolicy.BLOCK_CTA);
        node.put("label", label);
        node.put("href", href);
        node.put("variant", token(block, "variant", RichContentPolicy.CTA_VARIANTS,
                RichContentPolicy.DEFAULT_CTA_VARIANT));
        node.put("align", token(block, "align", RichContentPolicy.ALIGNMENTS,
                RichContentPolicy.DEFAULT_ALIGNMENT));
        return node;
    }

    private String ctaScoped(String linkCode) {
        return switch (linkCode) {
            case "error.richContent.linkRequired" -> "error.richContent.ctaHrefRequired";
            case "error.richContent.linkSchemeUnsupported",
                 "error.richContent.linkSchemeRequired" -> "error.richContent.ctaHrefUnsupported";
            default -> "error.richContent.ctaHrefMalformed";
        };
    }

    // ── Inline content ───────────────────────────────────────────────────────

    private ArrayNode sanitizeInlines(JsonNode content) {
        ArrayNode cleaned = JSON.createArrayNode();
        if (content == null || !content.isArray()) {
            return cleaned;
        }
        for (JsonNode inline : content) {
            if (cleaned.size() >= RichContentPolicy.MAX_INLINES_PER_BLOCK) {
                break;
            }
            if (inline == null || !inline.isObject()) {
                continue;
            }
            String type = text(inline, "type");
            if (type == null || !RichContentPolicy.INLINE_TYPES.contains(type)) {
                continue;
            }
            if (RichContentPolicy.INLINE_BREAK.equals(type)) {
                ObjectNode br = JSON.createObjectNode();
                br.put("type", RichContentPolicy.INLINE_BREAK);
                cleaned.add(br);
                continue;
            }

            String value = text(inline, "text");
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (value.length() > RichContentPolicy.MAX_TEXT_LENGTH) {
                value = value.substring(0, RichContentPolicy.MAX_TEXT_LENGTH);
            }

            ObjectNode node = JSON.createObjectNode();
            node.put("type", RichContentPolicy.INLINE_TEXT);
            node.put("text", value);
            ArrayNode marks = sanitizeMarks(inline.get("marks"));
            if (!marks.isEmpty()) {
                node.set("marks", marks);
            }
            cleaned.add(node);
        }

        // A run of line breaks with no text either side is formatting with nothing in it.
        return hasText(cleaned) ? cleaned : JSON.createArrayNode();
    }

    private ArrayNode sanitizeMarks(JsonNode marks) {
        ArrayNode cleaned = JSON.createArrayNode();
        if (marks == null || !marks.isArray()) {
            return cleaned;
        }
        // One of each at most, in a fixed order: two "bold" marks on one run are the same run, and
        // leaving both in would make two identical documents compare unequal.
        java.util.Map<String, ObjectNode> byType = new java.util.LinkedHashMap<>();
        for (JsonNode mark : marks) {
            if (mark == null || !mark.isObject()) {
                continue;
            }
            String type = text(mark, "type");
            if (type == null || !RichContentPolicy.MARK_TYPES.contains(type) || byType.containsKey(type)) {
                continue;
            }
            ObjectNode node = JSON.createObjectNode();
            node.put("type", type);

            if (RichContentPolicy.MARK_COLOR.equals(type)) {
                String value = token(mark, "value", RichContentPolicy.TEXT_COLORS,
                        RichContentPolicy.DEFAULT_TEXT_COLOR);
                // The default is what text already is; storing it would be noise in every diff.
                if (RichContentPolicy.DEFAULT_TEXT_COLOR.equals(value)) {
                    continue;
                }
                node.put("value", value);
            } else if (RichContentPolicy.MARK_LINK.equals(type)) {
                node.put("href", linkUrlPolicy.requireSafe(text(mark, "href")));
            }
            byType.put(type, node);
        }

        // Fixed emission order, independent of the order the client happened to send them in.
        for (String type : java.util.List.of(
                RichContentPolicy.MARK_BOLD, RichContentPolicy.MARK_ITALIC,
                RichContentPolicy.MARK_UNDERLINE, RichContentPolicy.MARK_STRIKE,
                RichContentPolicy.MARK_COLOR, RichContentPolicy.MARK_LINK)) {
            ObjectNode node = byType.get(type);
            if (node != null) {
                cleaned.add(node);
            }
        }
        return cleaned;
    }

    // ── Emptiness ────────────────────────────────────────────────────────────

    /**
     * Whether the document teaches anything.
     *
     * <p>Text that is only whitespace does not count, so a lesson of blank paragraphs, empty
     * headings and stray line breaks is refused however many of them there are. A call-to-action
     * does count on its own: a lesson that is a single "Start the exercise" button is thin, but it
     * is a deliberate thing to publish rather than an accident.
     */
    private boolean hasSomethingToRead(ArrayNode blocks) {
        for (JsonNode block : blocks) {
            String type = text(block, "type");
            if (RichContentPolicy.BLOCK_CTA.equals(type)) {
                return true;
            }
            if (hasNonBlankText(block.get("content"))) {
                return true;
            }
            JsonNode items = block.get("items");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    if (hasNonBlankText(item.get("content"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasNonBlankText(JsonNode content) {
        if (content == null || !content.isArray()) {
            return false;
        }
        for (JsonNode inline : content) {
            String value = text(inline, "text");
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(ArrayNode inlines) {
        for (JsonNode inline : inlines) {
            if (RichContentPolicy.INLINE_TEXT.equals(text(inline, "type"))) {
                return true;
            }
        }
        return false;
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    private String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /**
     * One design token, or the default.
     *
     * <p>An unrecognised token falls back rather than failing. These are presentation choices, and
     * a client sending a value this build does not know about — an older editor, a newer one —
     * should render as ordinary text, not refuse to save the lesson.
     */
    private String token(JsonNode node, String field, java.util.Set<String> allowed, String fallback) {
        String value = text(node, field);
        if (value == null) {
            return fallback;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(upper) ? upper : fallback;
    }
}
