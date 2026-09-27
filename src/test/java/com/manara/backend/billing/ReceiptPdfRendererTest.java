package com.manara.backend.billing;

import com.manara.backend.billing.service.ReceiptDocument;
import com.manara.backend.billing.service.ReceiptPdfRenderer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The receipt PDF: produced offline from bundled fonts, with Cairo embedded, and carrying the
 * simulation label when the payment was simulated. Set {@code manara.receipt.sample} to a path to
 * keep the rendered file for a visual check.
 */
class ReceiptPdfRendererTest {

    private final ReceiptPdfRenderer renderer = new ReceiptPdfRenderer();

    private static ReceiptDocument receipt(boolean simulated) {
        return new ReceiptDocument("DEMO-2026-000042", LocalDateTime.of(2026, 9, 27, 14, 5), simulated,
                "سارة أحمد", "sara@example.com", "الجبر للمرحلة الثانوية — فصلي (٣ شهر)",
                new BigDecimal("400.00"), "EGP", "5b2c1f0e-1d2a-4f6b-9c11-2f0f8a7d6e11", "sim_3a74c0df");
    }

    @Test
    @DisplayName("a simulated receipt is a valid PDF with Cairo embedded and says it is a simulation")
    void simulatedReceipt() throws Exception {
        byte[] pdf = renderer.render(receipt(true));
        String keep = System.getProperty("manara.receipt.sample");
        if (keep != null) Files.write(Path.of(keep), pdf);

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            Set<String> fonts = new HashSet<>();
            for (var name : document.getPage(0).getResources().getFontNames()) {
                PDFont font = document.getPage(0).getResources().getFont(name);
                fonts.add(font.getName());
                assertThat(font.isEmbedded()).as(font.getName() + " embedded").isTrue();
            }
            assertThat(fonts).anyMatch(name -> name.contains("Cairo"));
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("DEMO-2026-000042").contains("400.00 EGP").contains("sim_3a74c0df");
        }
    }

    @Test
    @DisplayName("values are escaped into the page, never interpreted as markup")
    void valuesAreEscaped() throws Exception {
        var hostile = new ReceiptDocument("R-1", LocalDateTime.now(), false, "<img src=http://evil.test/x>",
                "a@b.c", "</td></tr><script>x</script>", BigDecimal.ONE, "EGP", "t", null);
        byte[] pdf = renderer.render(hostile);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            // Shown as text. (In a right-to-left paragraph bidi mirrors the angle brackets.)
            assertThat(new PDFTextStripper().getText(document))
                    .contains("img src=http://evil.test/x")
                    .contains("script>x</script");
        }
    }
}
