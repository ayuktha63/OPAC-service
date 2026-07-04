package com.orque.opac.service;

/**
 * Wraps email content in the shared Orque HTML shell (header, content card, footer)
 * so every outbound email — template-based or hand-built — looks consistent.
 */
public final class EmailTemplateBuilder {

    private static final String BRAND_COLOR = "#4f46e5";

    private EmailTemplateBuilder() {}

    public static String wrap(String preheader, String bodyHtml) {
        return "<!DOCTYPE html>"
            + "<html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"></head>"
            + "<body style=\"margin:0;padding:0;background-color:#f4f5f7;font-family:'Segoe UI',Roboto,Helvetica,Arial,sans-serif;\">"
            + "<span style=\"display:none;max-height:0;overflow:hidden;\">" + esc(preheader) + "</span>"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#f4f5f7;padding:32px 0;\">"
            + "<tr><td align=\"center\">"
            + "<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#ffffff;border-radius:8px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,0.08);\">"
            + "<tr><td style=\"background:" + BRAND_COLOR + ";padding:24px 32px;\">"
            + "<span style=\"font-size:20px;font-weight:700;color:#ffffff;letter-spacing:0.5px;\">ORQUE</span>"
            + "</td></tr>"
            + "<tr><td style=\"padding:32px;color:#1f2937;font-size:15px;line-height:1.6;\">"
            + bodyHtml
            + "</td></tr>"
            + "<tr><td style=\"padding:20px 32px;background:#fafafa;border-top:1px solid #eee;color:#9ca3af;font-size:12px;\">"
            + "This is an automated message from the Orque Platform. Please do not reply directly to this email.<br>"
            + "&copy; " + java.time.Year.now() + " Orque. All rights reserved."
            + "</td></tr>"
            + "</table>"
            + "</td></tr>"
            + "</table>"
            + "</body></html>";
    }

    /** A bordered box used to highlight credentials/keys inside a body. */
    public static String credentialRow(String label, String value) {
        return "<tr>"
            + "<td style=\"padding:6px 0;color:#6b7280;font-size:13px;width:130px;\">" + esc(label) + "</td>"
            + "<td style=\"padding:6px 0;color:#111827;font-size:14px;font-weight:600;\">" + esc(value) + "</td>"
            + "</tr>";
    }

    public static String credentialsBox(String rowsHtml) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"background:#f9fafb;border:1px solid #e5e7eb;border-radius:6px;padding:16px 20px;margin:16px 0;\">"
            + rowsHtml
            + "</table>";
    }

    public static String button(String label, String url) {
        if (url == null || url.isBlank()) return "";
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:24px 0;\">"
            + "<tr><td style=\"border-radius:6px;background:" + BRAND_COLOR + ";\">"
            + "<a href=\"" + esc(url) + "\" style=\"display:inline-block;padding:12px 28px;color:#ffffff;"
            + "font-size:14px;font-weight:600;text-decoration:none;border-radius:6px;\">" + esc(label) + "</a>"
            + "</td></tr></table>";
    }

    /** Escapes a value for safe HTML interpolation (values may come from user-controlled request fields). */
    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Converts a plain-text block (with \n line breaks) into safe HTML paragraphs. */
    public static String textToHtml(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            sb.append(esc(line)).append("<br>");
        }
        return sb.toString();
    }
}
