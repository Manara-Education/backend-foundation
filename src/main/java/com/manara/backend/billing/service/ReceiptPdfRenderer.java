package com.manara.backend.billing.service;

import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import com.openhtmltopdf.bidi.support.ICUBidiSplitter;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;

/**
 * Renders a receipt as a PDF, on the server, from bundled resources only.
 *
 * <p>The page is an XHTML string built here with every value escaped; there are no external
 * stylesheets, images or fonts to fetch, so rendering cannot be pointed at a URL. Arabic is shaped
 * and laid out right-to-left by the ICU bidi support, with Cairo embedded. Numbers and references
 * are isolated left-to-right so mixed runs read correctly.
 *
 * <p>A simulated receipt says so in its heading and in a band across the page: it records a
 * demonstration, not money received, and it is never a tax invoice.
 */
@Component
public class ReceiptPdfRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public byte[] render(ReceiptDocument receipt) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
            builder.useUnicodeBidiReorderer(new ICUBidiReorderer());
            builder.defaultTextDirection(BaseRendererBuilder.TextDirection.RTL);
            builder.useFont(() -> font("Cairo-Regular.ttf"), "Cairo", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> font("Cairo-Bold.ttf"), "Cairo", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.withHtmlContent(html(receipt), null);
            builder.toStream(out);
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render receipt " + receipt.number(), e);
        }
        return out.toByteArray();
    }

    private static InputStream font(String name) {
        InputStream stream = ReceiptPdfRenderer.class.getResourceAsStream("/fonts/cairo/" + name);
        if (stream == null) {
            throw new IllegalStateException("Bundled font missing: " + name);
        }
        return stream;
    }

    /** XML escaping: the page is parsed as XHTML, which knows no HTML named entities. */
    private static String e(String value) {
        if (value == null) return "—";
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    // Control characters are not allowed in XML at all.
                    if (c >= 0x20 || c == '\n' || c == '\t') out.append(c);
                }
            }
        }
        return out.toString();
    }

    /** Left-to-right island for numbers, codes and addresses inside right-to-left text. */
    private static String ltr(String value) {
        return "<span class=\"ltr\">" + e(value) + "</span>";
    }

    static String html(ReceiptDocument r) {
        String amount = r.amount() == null ? "—" : r.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + (r.currency() == null ? "" : " " + r.currency());
        String title = r.simulated() ? "إيصال تجريبي — محاكاة دفع" : "إيصال دفع";
        String band = r.simulated()
                ? "<div class=\"band\">محاكاة دفع — لم تُحصَّل أي أموال. هذا الإيصال ليس فاتورة ضريبية.</div>"
                : "<div class=\"note\">إيصال غير ضريبي.</div>";
        return """
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" lang="ar" dir="rtl">
                <head>
                <meta charset="UTF-8"/>
                <title>%s</title>
                <style>
                  @page { size: A4; margin: 22mm 18mm; }
                  body { font-family: 'Cairo'; font-size: 11pt; color: #1E2340; direction: rtl; }
                  h1 { font-size: 20pt; font-weight: 700; color: #4E5B92; margin: 0 0 2mm 0; }
                  .brand { font-size: 13pt; font-weight: 700; color: #4E5B92; }
                  .ltr { direction: ltr; unicode-bidi: embed; }
                  .band { margin: 5mm 0; padding: 3mm 4mm; background: #FFF4DB; color: #8A5A00; font-weight: 700; }
                  .note { margin: 5mm 0; color: #717182; }
                  table { width: 100%%; border-collapse: collapse; margin-top: 4mm; }
                  th, td { text-align: right; padding: 2.5mm 3mm; border-bottom: 0.3mm solid #E3E6F1; vertical-align: top; }
                  th { width: 38%%; color: #717182; font-weight: 400; }
                  .total td, .total th { font-weight: 700; font-size: 13pt; color: #1E2340; }
                </style>
                </head>
                <body>
                  <div class="brand">منارة</div>
                  <h1>%s</h1>
                  %s
                  <table>
                    <tr><th>رقم الإيصال</th><td>%s</td></tr>
                    <tr><th>تاريخ الإصدار</th><td>%s</td></tr>
                    <tr><th>العميل</th><td>%s<br/>%s</td></tr>
                    <tr><th>البند</th><td>%s</td></tr>
                    <tr class="total"><th>الإجمالي</th><td>%s</td></tr>
                    <tr><th>مرجع العملية</th><td>%s</td></tr>
                    <tr><th>مرجع بوابة الدفع</th><td>%s</td></tr>
                  </table>
                </body>
                </html>
                """.formatted(
                e(title), e(title), band,
                ltr(r.number()),
                ltr(r.issuedAt() == null ? null : DATE.format(r.issuedAt())),
                e(r.customerName()), ltr(r.customerEmail()),
                e(r.lineDescription()),
                ltr(amount),
                ltr(r.transactionReference()),
                ltr(r.gatewayReference()));
    }
}
