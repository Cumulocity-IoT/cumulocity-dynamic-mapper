/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */

package dynamic.mapper.logs.read;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the records of one mapping out of a stored chunk's text.
 *
 * <p>A record is a header line plus its continuation lines (stack trace, multi-line payload). The header is what
 * {@code ServiceLogCaptureAppender.PATTERN} prints: timestamp, level, {@code [thread]}, then — for a line written while
 * a mapping processed a payload — {@code [m:<identifier>] }. The tag is only looked for at that fixed position, never
 * anywhere in the line, so a payload that happens to contain {@code [m:x]} does not count.
 *
 * <p>Known limit: a logged payload that itself contains a line that looks like a header (timestamp, level, thread
 * in brackets) starts a new record. It can only affect which of the tenant's own lines are shown, never another
 * tenant's: isolation is decided before the text gets here.
 */
public final class MappingLogRecords {

    private MappingLogRecords() {
    }

    /** Timestamp, level and thread of a header line, then the optional mapping tag. */
    private static final Pattern HEADER = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[.,]\\d+\\S* +[A-Z]+ \\[[^\\]\\n]*\\] (?:\\[m:([A-Za-z0-9_-]{1,64})\\] )?");

    /** Whether the first line of the record {@code text} is a header that carries the tag of {@code mapping}. */
    public static boolean carriesTag(String text, String mapping) {
        if (text == null || mapping == null) {
            return false;
        }
        int eol = text.indexOf('\n');
        Matcher header = HEADER.matcher(eol < 0 ? text : text.substring(0, eol));
        return header.find() && mapping.equals(header.group(1));
    }

    /** The records of {@code logs} whose header carries {@code mapping}, joined by newlines; empty if there are none. */
    public static String filter(String logs, String mapping) {
        if (logs == null || logs.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        StringBuilder record = new StringBuilder();
        boolean keep = false;
        for (String line : logs.split("\\n", -1)) {
            Matcher header = HEADER.matcher(line);
            if (header.find()) {
                flush(out, record, keep);
                record.setLength(0);
                keep = mapping.equals(header.group(1));
            }
            // A continuation line before any header belongs to no record that could match.
            if (record.length() > 0) {
                record.append('\n');
            }
            record.append(line);
        }
        flush(out, record, keep);
        return out.toString();
    }

    private static void flush(StringBuilder out, StringBuilder record, boolean keep) {
        if (keep && record.length() > 0) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(record);
        }
    }

    /** Number of lines in {@code text}; 0 for the empty string. */
    public static int lines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
