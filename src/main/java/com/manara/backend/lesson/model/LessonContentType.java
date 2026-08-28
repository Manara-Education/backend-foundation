package com.manara.backend.lesson.model;

/**
 * What a lesson actually teaches with.
 *
 * <p>A first-class discriminator, deliberately, rather than something read off whether
 * {@code video_url} happens to be null. The prototype had no such column and every surface
 * inferred the answer for itself — the student player from {@code lesson.videoUrl}, the validator
 * from the same field being blank — which meant a lesson's kind was a different question on every
 * screen and could not be authored, only stumbled into. Storing it makes the instructor's choice
 * the fact, and the content columns its consequence.
 *
 * <p>Both content columns survive a change of type; see
 * {@link com.manara.backend.lesson.content.RichContent} for why nothing is thrown away. Which one
 * is <em>read</em> is this enum's decision alone.
 *
 * <h2>Adding one</h2>
 * A new type — {@code AUDIO}, {@code PDF}, {@code LIVE_SESSION} — is a constant here, a branch in
 * {@link com.manara.backend.lesson.validation.LessonContentValidator}, and whatever column it needs.
 * Nothing else switches on this: progress, completion, ordering and update tracking are all written
 * against a lesson rather than against a kind of lesson, which is the property worth protecting.
 */
public enum LessonContentType {

    /** A hosted video — YouTube, Vimeo, and whatever adapter is added next. */
    VIDEO,

    /**
     * Instructor-authored educational content: headings, prose, lists, links and call-to-action
     * buttons, held as a structured document rather than as markup.
     */
    RICH_CONTENT
}
