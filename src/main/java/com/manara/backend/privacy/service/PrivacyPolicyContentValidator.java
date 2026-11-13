package com.manara.backend.privacy.service;

import com.manara.backend.privacy.model.PrivacyPolicyContent;
import com.manara.backend.privacy.model.PrivacyPolicyContent.Block;
import com.manara.backend.privacy.model.PrivacyPolicyContent.Section;
import com.manara.backend.privacy.model.PrivacyPolicyVersion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Everything a privacy-policy version must satisfy before the application will start with it.
 *
 * <p>Run against every version in the catalogue, candidates included, so a broken draft fails the
 * build long before anyone tries to publish it. The checks are structural and editorial, never
 * legal: they cannot tell whether a sentence is true, only that the document is complete, carries
 * no leftover drafting notes, and that a published text has not been edited in place.
 */
final class PrivacyPolicyContentValidator {

    static final String POLICY_ID = "privacy-policy";

    private static final Pattern SECTION_ID = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Set<String> BLOCK_TYPES = Set.of("paragraph", "list", "email");

    /**
     * Drafting residue that must never reach a public policy: the frontend draft's gap-note
     * phrasing, template braces and engineering markers.
     */
    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "قيد التحديد", "قيد المراجعة", "مسودة", "TODO", "TBD", "FIXME", "{{", "}}", "PLACEHOLDER", "lorem");

    private PrivacyPolicyContentValidator() {
    }

    static List<String> problems(PrivacyPolicyVersion version, PrivacyPolicyContent content, String sha256) {
        List<String> problems = new ArrayList<>();

        if (!POLICY_ID.equals(content.policyId())) problems.add("policyId must be '" + POLICY_ID + "'");
        if (!version.id().equals(content.version())) problems.add("content version '" + content.version()
                + "' does not match catalogue id '" + version.id() + "'");
        if (!version.language().equals(content.language())) problems.add("content language '" + content.language()
                + "' does not match catalogue language '" + version.language() + "'");
        requireText(problems, "title", content.title());
        requireText(problems, "summary", content.summary());

        PrivacyPolicyContent.Operator operator = content.operator();
        if (operator == null) {
            problems.add("operator is missing");
        } else {
            requireText(problems, "operator.name", operator.name());
            if (operator.privacyEmail() == null || !EMAIL.matcher(operator.privacyEmail()).matches()) {
                problems.add("operator.privacyEmail is not a single email address");
            }
        }

        if (content.sections() == null || content.sections().isEmpty()) {
            problems.add("there are no sections");
        } else {
            Set<String> ids = new HashSet<>();
            for (Section section : content.sections()) {
                checkSection(problems, section, ids, operator);
            }
        }

        if (version.isPublished()) {
            if (version.effectiveDate() == null) problems.add("a published version needs an effective date");
            if (version.contentSha256() == null) {
                problems.add("a published version needs its content hash pinned (currently " + sha256 + ")");
            } else if (!version.contentSha256().equalsIgnoreCase(sha256)) {
                problems.add("published content changed after publication: pinned " + version.contentSha256()
                        + ", file is " + sha256 + ". Publish a new version instead of editing this one.");
            }
        } else if (version.effectiveDate() != null) {
            problems.add("a candidate has no effective date until it is published");
        }

        for (String fragment : FORBIDDEN_FRAGMENTS) {
            if (containsAnywhere(content, fragment)) problems.add("contains drafting residue '" + fragment + "'");
        }
        return problems;
    }

    private static void checkSection(List<String> problems, Section section, Set<String> ids,
                                     PrivacyPolicyContent.Operator operator) {
        String where = "section '" + section.id() + "'";
        if (section.id() == null || !SECTION_ID.matcher(section.id()).matches()) {
            problems.add(where + ": id must be lowercase letters, digits and hyphens");
        } else if (!ids.add(section.id())) {
            problems.add(where + ": duplicate id");
        }
        requireText(problems, where + " number", section.number());
        requireText(problems, where + " title", section.title());
        if (section.blocks() == null || section.blocks().isEmpty()) {
            problems.add(where + ": has no content");
            return;
        }
        for (Block block : section.blocks()) {
            checkBlock(problems, where, block, operator);
        }
    }

    private static void checkBlock(List<String> problems, String where, Block block,
                                   PrivacyPolicyContent.Operator operator) {
        if (block.type() == null || !BLOCK_TYPES.contains(block.type())) {
            problems.add(where + ": unknown block type '" + block.type() + "'");
            return;
        }
        switch (block.type()) {
            case "paragraph" -> {
                requireText(problems, where + " paragraph", block.text());
                if (block.items() != null || block.address() != null) problems.add(where + ": paragraph carries list or email fields");
            }
            case "list" -> {
                if (block.items() == null || block.items().isEmpty()) problems.add(where + ": empty list");
                else block.items().forEach(item -> requireText(problems, where + " list item", item));
                if (block.text() != null || block.address() != null) problems.add(where + ": list carries paragraph or email fields");
            }
            case "email" -> {
                requireText(problems, where + " email label", block.label());
                // The address in the text and the operator's published address cannot drift apart.
                if (operator == null || block.address() == null || !block.address().equals(operator.privacyEmail())) {
                    problems.add(where + ": email block must carry operator.privacyEmail exactly");
                }
            }
            default -> throw new IllegalStateException(block.type());
        }
    }

    private static void requireText(List<String> problems, String field, String value) {
        if (value == null || value.isBlank()) problems.add(field + " is blank");
    }

    private static boolean containsAnywhere(PrivacyPolicyContent content, String fragment) {
        List<String> texts = new ArrayList<>();
        texts.add(content.title());
        texts.add(content.summary());
        if (content.sections() != null) {
            for (Section section : content.sections()) {
                texts.add(section.title());
                if (section.blocks() == null) continue;
                for (Block block : section.blocks()) {
                    texts.add(block.text());
                    texts.add(block.label());
                    if (block.items() != null) texts.addAll(block.items());
                }
            }
        }
        String needle = fragment.toLowerCase();
        return texts.stream().anyMatch(text -> text != null && text.toLowerCase().contains(needle));
    }
}
