package com.hpg.common.masking;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Last-line redaction of free text before it is written to logs (docs/09-security.md §7). The primary control
 * is never logging sensitive objects; this catches what slips through: email addresses, phone numbers,
 * bearer/basic credentials, {@code secret=}/{@code password=} values and card-number-like digit runs.
 *
 * <p>Card numbers are only masked when the digits pass the Luhn check, so ids, epoch timestamps and amounts
 * (which fail it almost always) stay readable.
 */
public final class LogMasking {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern AUTH = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)\\b((?:key_?secret|client_?secret|webhook_?secret|secret|password|passwd|api_?key)\\s*[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"',&}]{4,})");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w+])\\+\\d{1,3}[ -]?\\d{6,12}(?!\\d)");
    private static final Pattern CARD = Pattern.compile("(?<![\\d-])(?:\\d[ -]?){12,18}\\d(?![\\d-])");

    private LogMasking() {
    }

    public static String mask(String text) {
        if (text == null || text.length() < 8) {
            return text;
        }
        String out = replace(EMAIL, text, m -> Masking.email(m.group()));
        out = replace(AUTH, out, m -> m.group(1) + " ****");
        out = replace(SECRET_VALUE, out, m -> m.group(1) + "****");
        out = replace(PHONE, out, m -> Masking.phone(m.group().replaceAll("[ -]", "")));
        out = replace(CARD, out, m -> luhn(m.group()) ? "[REDACTED-CARD]" : m.group());
        return out;
    }

    private interface Replacer {
        String apply(Matcher m);
    }

    private static String replace(Pattern p, String text, Replacer replacer) {
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        do {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m)));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    static boolean luhn(String candidate) {
        String digits = candidate.replaceAll("[ -]", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }
}
