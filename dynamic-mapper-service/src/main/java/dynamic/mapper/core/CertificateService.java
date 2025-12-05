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

package dynamic.mapper.core;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.rest.representation.CumulocityMediaType;
import com.cumulocity.sdk.client.Platform;

import dynamic.mapper.configuration.TrustedCertificateCollectionRepresentation;
import dynamic.mapper.configuration.TrustedCertificateRepresentation;
import dynamic.mapper.connector.core.client.Certificate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CertificateService {

    @Autowired
    private Platform platform;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    public Certificate loadCertificateByName(String certificateName, String fingerprint,
            String tenant, String connectorName) {
        TrustedCertificateRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
            log.info("{} - Connector {} - Retrieving certificate {} ", tenant, connectorName, certificateName);
            TrustedCertificateRepresentation certResult = null;
            try {
                List<TrustedCertificateRepresentation> certificatesList = new ArrayList<>();
                String nextUrl = String.format("/tenant/tenants/%s/trusted-certificates", tenant);

                // Pagination with safety limit
                int pageCount = 0;
                int maxPages = 100; // Safety limit to prevent infinite loops

                while (nextUrl != null && !nextUrl.isEmpty() && pageCount < maxPages) {
                    pageCount++;

                    TrustedCertificateCollectionRepresentation certificatesResult = platform.rest().get(
                            nextUrl,
                            CumulocityMediaType.APPLICATION_JSON_TYPE,
                            TrustedCertificateCollectionRepresentation.class);

                    List<TrustedCertificateRepresentation> pageCerts = certificatesResult.getCertificates();

                    if (pageCerts != null && !pageCerts.isEmpty()) {
                        certificatesList.addAll(pageCerts);
                        log.debug("{} - Connector {} - Page {}: Retrieved {} certificates (total: {})",
                                tenant, connectorName, pageCount, pageCerts.size(), certificatesList.size());
                    } else {
                        log.debug("{} - Connector {} - Page {}: No more certificates",
                                tenant, connectorName, pageCount);
                        break;
                    }

                    nextUrl = certificatesResult.getNext();

                    if (nextUrl == null || nextUrl.isEmpty()) {
                        log.debug("{} - Connector {} - No more pages (nextUrl is null/empty)",
                                tenant, connectorName);
                        break;
                    }
                }

                if (pageCount >= maxPages) {
                    log.warn("{} - Connector {} - Reached maximum page limit ({}), stopping pagination",
                            tenant, connectorName, maxPages);
                }

                log.info("{} - Connector {} - Retrieved total of {} certificates across {} pages",
                        tenant, connectorName, certificatesList.size(), pageCount);

                // Search for matching certificate
                for (TrustedCertificateRepresentation certificateIterate : certificatesList) {
                    log.debug("{} - Checking certificate: name='{}', fingerprint='{}'",
                            tenant, certificateIterate.getName(), certificateIterate.getFingerprint());

                    String normalizedStoredFingerprint = normalizeFingerprint(certificateIterate.getFingerprint());
                    String normalizedInputFingerprint = normalizeFingerprint(fingerprint);

                    boolean nameMatches = certificateIterate.getName().equals(certificateName);
                    boolean fingerprintMatches = normalizedStoredFingerprint.equals(normalizedInputFingerprint);

                    if (nameMatches && fingerprintMatches) {
                        certResult = certificateIterate;
                        log.info("{} - Connector {} - Found matching certificate: name='{}', fingerprint='{}'",
                                tenant, connectorName, certificateName, certificateIterate.getFingerprint());
                        break;
                    } else if (nameMatches) {
                        log.debug("{} - Name matches but fingerprint differs: expected='{}', got='{}'",
                                tenant, normalizedInputFingerprint, normalizedStoredFingerprint);
                    }
                }

                if (certResult == null) {
                    log.warn("{} - Connector {} - Certificate not found: name='{}', fingerprint='{}'",
                            tenant, connectorName, certificateName, fingerprint);
                }

            } catch (Exception e) {
                log.error("{} - Connector {} - Error retrieving certificate: ", tenant, connectorName, e);
            }
            return certResult;
        });

        if (result != null) {
            return buildCertificate(result, tenant, connectorName, certificateName, fingerprint);
        } else {
            log.warn("{} - Connector {} - No certificate found with name='{}' and fingerprint='{}'",
                    tenant, connectorName, certificateName, fingerprint);
            return null;
        }
    }

    private Certificate buildCertificate(TrustedCertificateRepresentation result, String tenant,
            String connectorName, String certificateName, String fingerprint) {
        log.info("{} - Connector {} - Found certificate '{}' with fingerprint '{}'",
                tenant, connectorName, certificateName, result.getFingerprint());

        String pemContent = result.getCertInPemFormat();
        String fullPemCert;

        if (pemContent != null && pemContent.contains("-----BEGIN CERTIFICATE-----")) {
            fullPemCert = pemContent;
            log.debug("{} - Certificate already in PEM format with markers", tenant);
        } else if (pemContent != null && !pemContent.isEmpty()) {
            fullPemCert = "-----BEGIN CERTIFICATE-----\n" + pemContent + "\n-----END CERTIFICATE-----";
            log.debug("{} - Added PEM markers to certificate", tenant);
        } else {
            log.error("{} - Certificate PEM content is null or empty", tenant);
            return null;
        }

        int certCount = countCertificatesInChain(fullPemCert);
        log.info("{} - Certificate chain contains {} certificate(s)", tenant, certCount);

        return new Certificate(result.getFingerprint(), fullPemCert);
    }

    private String normalizeFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return "";
        }
        return fingerprint.replace(":", "").replace(" ", "").trim().toLowerCase();
    }

    private int countCertificatesInChain(String pemContent) {
        if (pemContent == null)
            return 0;

        int count = 0;
        int index = 0;
        while ((index = pemContent.indexOf("-----BEGIN CERTIFICATE-----", index)) != -1) {
            count++;
            index += 27;
        }
        return count;
    }
}