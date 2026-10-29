/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.connector.core.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertificatePemTest {

    private static final String BEGIN = "-----BEGIN CERTIFICATE-----";
    private static final String END = "-----END CERTIFICATE-----";
    private static final String BODY = "\nMIIBszCCAVmgAwIBAgIUQ3pT+abc/def==\nMIIBszCCAVmgAwIBAgIUQ3pT\n";

    @Test
    void aBlockWithABase64BodyIsWellFormed() {
        assertTrue(Certificate.containsWellFormedCertificate(BEGIN + BODY + END));
    }

    @Test
    void aWellFormedBlockAfterOtherTextIsFound() {
        assertTrue(Certificate.containsWellFormedCertificate("junk\n" + BEGIN + "\n!!\n" + END + "\n" + BEGIN + BODY + END));
    }

    @Test
    void theBodyMustStartWithWhitespace() {
        assertFalse(Certificate.containsWellFormedCertificate(BEGIN + "MIIB" + END));
    }

    @Test
    void aCharacterOutsideBase64MakesTheBlockMalformed() {
        assertFalse(Certificate.containsWellFormedCertificate(BEGIN + "\nMIIB$MIIB\n" + END));
    }

    @Test
    void anEmptyBodyIsMalformed() {
        assertFalse(Certificate.containsWellFormedCertificate(BEGIN + END));
    }

    @Test
    void aMissingEndMarkerIsMalformed() {
        assertFalse(Certificate.containsWellFormedCertificate(BEGIN + BODY));
    }

    @Test
    void manyHeadersWithOneFinalEndMarkerAreRejectedInLinearTime() {
        // the last header is followed by the footer without whitespace, so no block is well-formed
        String malformed = (BEGIN + "\n").repeat(40_000) + BEGIN + END;

        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertFalse(Certificate.containsWellFormedCertificate(malformed)));
    }

    @Test
    void aValidBlockAfterManyMalformedHeadersIsStillFound() {
        String input = (BEGIN + "\n").repeat(40_000) + "\n" + BEGIN + BODY + END;

        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertTrue(Certificate.containsWellFormedCertificate(input)));
    }
}
