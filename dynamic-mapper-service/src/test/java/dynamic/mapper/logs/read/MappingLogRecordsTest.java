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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MappingLogRecordsTest {

    private static String line(String time, String tag, String text) {
        return "2026-10-16T04:13:" + time + "Z  INFO [virtThread-39] " + tag + "d.m.p.Foo : " + text;
    }

    private static final String A = "[m:aaa] ";
    private static final String B = "[m:bbb] ";

    @Test
    void onlyTheRecordsOfTheMappingAreKeptInTheirOrder() {
        String logs = String.join("\n",
                line("01.001", "", "connector line"),
                line("02.002", A, "a1"),
                line("03.003", B, "b1"),
                line("04.004", A, "a2"));

        assertEquals(line("02.002", A, "a1") + "\n" + line("04.004", A, "a2"), MappingLogRecords.filter(logs, "aaa"));
        assertEquals(line("03.003", B, "b1"), MappingLogRecords.filter(logs, "bbb"));
        assertEquals("", MappingLogRecords.filter(logs, "ccc"));
    }

    @Test
    void continuationLinesStayWithTheirRecord() {
        String logs = String.join("\n",
                line("01.001", A, "failed"),
                "java.lang.IllegalStateException: boom",
                "\tat d.m.Foo.bar(Foo.java:1)",
                line("02.002", B, "other"),
                "\tat d.m.Baz.qux(Baz.java:2)");

        assertEquals(line("01.001", A, "failed") + "\njava.lang.IllegalStateException: boom\n\tat d.m.Foo.bar(Foo.java:1)",
                MappingLogRecords.filter(logs, "aaa"));
        assertEquals(line("02.002", B, "other") + "\n\tat d.m.Baz.qux(Baz.java:2)", MappingLogRecords.filter(logs, "bbb"));
    }

    @Test
    void aTagInsideTheMessageIsNotATag() {
        String logs = String.join("\n",
                line("01.001", "", "payload was [m:aaa] weird"),
                line("02.002", B, "mentions " + A));

        assertEquals("", MappingLogRecords.filter(logs, "aaa"));
    }

    @Test
    void aRecordWithTheTagInAContinuationLineDoesNotMatch() {
        String logs = line("01.001", "", "multi-line payload") + "\n" + A + "d.m.Foo : looks like a tag";

        assertEquals("", MappingLogRecords.filter(logs, "aaa"));
    }

    @Test
    void aThreadNameWithSpacesAndDigitsStillParses() {
        String logs = "2026-10-16T04:13:01.001Z DEBUG [pool-3-thread-12] [m:aaa] d.m.Foo : x";

        assertEquals(logs, MappingLogRecords.filter(logs, "aaa"));
    }

    @Test
    void emptyInputAndAnUntaggedLogGiveNothing() {
        assertEquals("", MappingLogRecords.filter(null, "aaa"));
        assertEquals("", MappingLogRecords.filter("", "aaa"));
        assertEquals("", MappingLogRecords.filter(line("01.001", "", "x"), "aaa"));
    }

    @Test
    void linesAreCountedPerLine() {
        assertEquals(0, MappingLogRecords.lines(""));
        assertEquals(1, MappingLogRecords.lines("x"));
        assertEquals(3, MappingLogRecords.lines("x\ny\nz"));
    }
}
