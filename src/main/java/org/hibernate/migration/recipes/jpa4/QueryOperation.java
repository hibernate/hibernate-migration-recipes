package org.hibernate.migration.recipes.jpa4;

import java.util.Locale;

/// Recognizes leading query operations and native SQL constructs that prevent safe
/// named-query conversion.
///
/// Recognition is deliberately conservative and does not validate a query grammar.
/// Callers use the leading token to classify the operation and the native SQL scan
/// to detect result clauses or syntax this scanner cannot interpret safely.
///
/// @author Jennifer Joby
/// @author Steve Ebersole
final class QueryOperation {
    private QueryOperation() {}

    /// Extracts the leading token after whitespace and normalizes it using [Locale#ROOT].
    ///
    /// Consumes Java identifier-part code points so a keyword prefix, such as
    /// `update` in `updateSomething`, is not mistaken for a complete operation.
    /// Leading comments and punctuation are not skipped, and CTEs are not traversed
    /// to find their eventual operation.
    ///
    /// @param query the non-null query text
    /// @return the uppercase leading token, or an empty string if no token begins
    /// immediately after leading whitespace
    static String first(String query) {
        int start = 0;
        while (start < query.length() && Character.isWhitespace(query.charAt(start))) start++;
        int end = start;
        while (end < query.length() && Character.isJavaIdentifierPart(query.codePointAt(end))) end += Character.charCount(query.codePointAt(end));
        return query.substring(start, end).toUpperCase(Locale.ROOT);
    }

    /// Scans native SQL for constructs that block automatic statement conversion.
    ///
    /// Ignores tokens inside line comments, non-nested block comments, and quoted
    /// strings or identifiers delimited by single quotes, double quotes, backticks,
    /// or brackets. Doubled closing delimiters are supported. A single terminal
    /// semicolon may be followed only by whitespace or comments.
    ///
    /// Unquoted `RETURNING` and `OUTPUT` tokens indicate a possible result clause.
    /// Unterminated or nested block comments, unterminated quotes, backslashes within
    /// quotes, dollar-containing tokens, Oracle-style `q'` or `nq'` quoting, and
    /// content after a terminal semicolon are treated as unsupported syntax.
    /// Tokens are inspected without grammatical context, so this scan can also
    /// reject an unquoted identifier named `returning` or `output`.
    ///
    /// @param sql the non-null native SQL text
    /// @return `NATIVE_RESULT_CLAUSE` for a detected result-clause token,
    /// `UNSUPPORTED_NATIVE_SYNTAX` for syntax the scanner cannot safely handle,
    /// or `null` when no blocker is detected; `null` does not establish SQL validity
    static String nativeBlocker(String sql) {
        boolean terminated = false;
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                int nested = sql.indexOf("/*", i + 2);
                if (end < 0 || nested >= 0 && nested < end) {
                    return "UNSUPPORTED_NATIVE_SYNTAX";
                }
                i = end + 2;
                continue;
            }
            if (terminated) {
                return "UNSUPPORTED_NATIVE_SYNTAX";
            }
            if (c == ';') {
                terminated = true; i++;
                continue;
            }
            if (c == '$') {
                return "UNSUPPORTED_NATIVE_SYNTAX";
            }
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                boolean closed = false;
                i++;
                while (i < sql.length()) {
                    char q = sql.charAt(i++);
                    if (q == '\\') {
                        return "UNSUPPORTED_NATIVE_SYNTAX";
                    }
                    if (q == close) {
                        if (i < sql.length() && sql.charAt(i) == close) {
                            i++;
                        }
                        else {
                            closed = true;
                            break;
                        }
                    }
                }
                if (!closed) {
                    return "UNSUPPORTED_NATIVE_SYNTAX";
                }
                continue;
            }
            if (Character.isJavaIdentifierPart(sql.codePointAt(i))) {
                int start = i;
                i += Character.charCount(sql.codePointAt(i));
                while (i < sql.length() && Character.isJavaIdentifierPart(sql.codePointAt(i))) {
                    i += Character.charCount(sql.codePointAt(i));
                }
                String token = sql.substring(start, i).toUpperCase(Locale.ROOT);
                if ((token.equals("Q") || token.equals("NQ")) && i < sql.length() && sql.charAt(i) == '\'') {
                    return "UNSUPPORTED_NATIVE_SYNTAX";
                }
                if (token.equals("RETURNING") || token.equals("OUTPUT")) {
                    return "NATIVE_RESULT_CLAUSE";
                }
                // A dollar quote can start adjacent to an identifier in malformed/ambiguous SQL.
                if (token.indexOf('$') >= 0) {
                    return "UNSUPPORTED_NATIVE_SYNTAX";
                }
            }
            else {
                i++;
            }
        }
        return null;
    }
}
