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

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.core.C8YAgent;
import lombok.extern.slf4j.Slf4j;

/**
 * Certificate/truststore/hostname-verification support for connector clients, extracted out of
 * {@link AConnectorClient} so this logic is independently testable without instantiating a full
 * connector subclass. Owned one-per-connector by {@link AConnectorClient#initializeManagers()}.
 * <p>
 * Callers (mostly {@link AConnectorClient}'s thin delegating wrappers, kept for subclass source
 * compatibility) are responsible for passing the current {@link ConnectorConfiguration} on every
 * call, since it can be reloaded over the lifetime of a connector.
 */
@Slf4j
public class ConnectorSslSupport {

    protected static final List<String> DEFAULT_TLS_PROTOCOLS = Arrays.asList("TLSv1.2", "TLSv1.3");
    protected static final String CACERTS_PASSWORD = "changeit";

    private final String tenant;
    private final String connectorName;
    private final C8YAgent c8yAgent;

    public ConnectorSslSupport(String tenant, String connectorName, C8YAgent c8yAgent) {
        this.tenant = tenant;
        this.connectorName = connectorName;
        this.c8yAgent = c8yAgent;
    }

    /** Result of {@link #initializeSsl(ConnectorConfiguration)}: the built SSL context, and — if a
     * custom certificate was loaded — the parsed {@link Certificate} (null when system default
     * certificates were used). */
    public record SslInitResult(SSLContext sslContext, Certificate cert) {
    }

    /**
     * Build the SSL context for a connection known to require SSL/TLS, either from system default
     * CAs or from a custom certificate configured on the connector (inline PEM or C8Y certificate
     * store), depending on {@code useSelfSignedCertificate}.
     * @throws Exception if certificate loading or SSL context initialization fails
     */
    public SslInitResult initializeSsl(ConnectorConfiguration connectorConfiguration) throws Exception {
        log.info("{} - Initializing SSL configuration", tenant);

        Boolean useSelfSignedCertificate = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);

        if (!useSelfSignedCertificate) {
            log.info("{} - Using system default SSL certificates", tenant);
            KeyStore trustStore = createTrustStore(true, null, null);
            TrustManagerFactory tmf = createTrustManagerFactory(trustStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            return new SslInitResult(sslContext, null);
        }

        log.info("{} - Loading custom SSL certificate", tenant);
        Certificate cert = loadCertificateFromConfiguration(connectorConfiguration);

        if (cert == null) {
            throw new ConnectorException("Failed to load SSL certificate");
        }

        logCertificateInfo(cert);

        KeyStore trustStore = createTrustStore(true, cert.getX509Certificates(), cert);
        TrustManagerFactory tmf = createTrustManagerFactory(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);

        log.info("{} - SSL configuration initialized successfully", tenant);
        return new SslInitResult(sslContext, cert);
    }

    /**
     * Validate certificate configuration.
     * Checks if certificate is properly configured when SSL is required.
     * @return true if certificate configuration is valid or not required
     */
    public boolean validateCertificateConfig(ConnectorConfiguration configuration) {
        Boolean useSelfSignedCertificate = (Boolean) configuration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);

        if (!useSelfSignedCertificate) {
            return true; // System certificates will be used
        }

        // Check for inline PEM certificate
        String certificateChainInPemFormat = (String) configuration.getProperties()
                .get("certificateChainInPemFormat");
        if (certificateChainInPemFormat != null && !certificateChainInPemFormat.trim().isEmpty()) {
            return true;
        }

        // Check for C8Y certificate store reference
        String nameCertificate = (String) configuration.getProperties().get("nameCertificate");
        String fingerprint = (String) configuration.getProperties().get("fingerprintSelfSignedCertificate");

        if (nameCertificate != null && !nameCertificate.trim().isEmpty() &&
            fingerprint != null && !fingerprint.trim().isEmpty()) {
            return true;
        }

        log.warn("{} - SSL certificate configuration incomplete. Either provide 'certificateChainInPemFormat' " +
                 "or both 'nameCertificate' and 'fingerprintSelfSignedCertificate'", tenant);
        return false;
    }

    /**
     * Load certificate from configuration properties.
     * Supports both inline PEM and C8Y certificate store.
     */
    public Certificate loadCertificateFromConfiguration(ConnectorConfiguration connectorConfiguration) throws ConnectorException {
        String nameCertificate = (String) connectorConfiguration.getProperties().get("nameCertificate");
        String fingerprint = (String) connectorConfiguration.getProperties()
                .get("fingerprintSelfSignedCertificate");
        String certificateChainInPemFormat = (String) connectorConfiguration.getProperties()
                .get("certificateChainInPemFormat");

        // Option 1: Load from inline PEM
        if (certificateChainInPemFormat != null && !certificateChainInPemFormat.isEmpty()) {
            log.info("{} - Using certificate chain from configuration property", tenant);
            return Certificate.fromPem(certificateChainInPemFormat);
        }

        // Option 2: Load from C8Y certificate store
        if (nameCertificate != null && fingerprint != null) {
            log.info("{} - Loading certificate from C8Y: name={}, fingerprint={}",
                    tenant, nameCertificate, fingerprint);
            Certificate loadedCert = c8yAgent.loadCertificateByName(
                    nameCertificate, fingerprint, tenant, connectorName);

            if (loadedCert == null) {
                throw new ConnectorException(
                        String.format("Certificate %s with fingerprint %s not found",
                                nameCertificate, fingerprint));
            }
            return loadedCert;
        }

        throw new ConnectorException(
                "Either 'certificateChainInPemFormat' or both 'nameCertificate' and 'fingerprint' must be provided");
    }

    /**
     * Log certificate information
     */
    public void logCertificateInfo(Certificate cert) {
        log.info("{} - Certificate Summary:", tenant);
        log.info("{}   Total certificates: {}", tenant, cert.getCertificateCount());
        log.info("{}   Is chain: {}", tenant, cert.isChain());
        log.info("{}   Is valid: {}", tenant, cert.isValid());
        log.info("{}   Chain ordered: {}", tenant, cert.isChainOrdered());

        // Check for validation errors
        List<String> validationErrors = cert.getValidationErrors();
        if (!validationErrors.isEmpty()) {
            log.warn("{} - Certificate validation warnings:", tenant);
            validationErrors.forEach(error -> log.warn("{}   - {}", tenant, error));
        }

        // Log detailed certificate information
        List<Certificate.CertificateInfo> certInfos = cert.getCertificateInfoList();
        for (Certificate.CertificateInfo info : certInfos) {
            log.info("{} - Certificate [{}] ({}):", tenant, info.getIndex(), info.getCertificateType());
            log.info("{}     CN: {}", tenant, info.getCommonName());
            log.info("{}     Issuer CN: {}", tenant, info.getIssuerCommonName());
            log.info("{}     Serial: {}", tenant, info.getSerialNumber());
            log.info("{}     Valid: {} to {}", tenant, info.getNotBefore(), info.getNotAfter());
            log.info("{}     Signature: {}", tenant, info.getSignatureAlgorithm());
            log.info("{}     Public Key: {} ({} bits)", tenant,
                    info.getPublicKeyAlgorithm(), info.getPublicKeySize());

            List<String> sans = info.getSubjectAlternativeNames();
            if (!sans.isEmpty()) {
                log.info("{}     SANs: {}", tenant, String.join(", ", sans));
            }
        }

        // Verify certificate chain cryptographically
        if (cert.isChain() && !cert.verifyChain()) {
            log.warn("{} - Certificate chain verification failed - signatures may not be valid", tenant);
        } else if (cert.isChain()) {
            log.info("{} - Certificate chain verification successful", tenant);
        }
    }

    /**
     * Create truststore with system CA certificates and custom certificates
     *
     * @param includeSystemCAs   if true, loads default Java cacerts; if false,
     *                           creates empty truststore
     * @param customCertificates list of custom certificates to add
     * @param cert               the Certificate object containing certificate info
     *                           (can be null if no custom certs)
     * @return configured KeyStore
     */
    public KeyStore createTrustStore(boolean includeSystemCAs, List<X509Certificate> customCertificates,
            Certificate cert)
            throws Exception {

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());

        int systemCertCount = 0;
        if (includeSystemCAs) {
            String cacertsPath = System.getProperty("java.home") + "/lib/security/cacerts";
            try (java.io.FileInputStream fis = new java.io.FileInputStream(cacertsPath)) {
                trustStore.load(fis, CACERTS_PASSWORD.toCharArray());
                systemCertCount = trustStore.size();
                log.info("{} - Loaded default cacerts from {} with {} system certificates",
                        tenant, cacertsPath, systemCertCount);
            } catch (Exception e) {
                log.warn("{} - Could not load default cacerts: {}, creating empty truststore",
                        tenant, e.getMessage());
                trustStore.load(null, null);
            }
        } else {
            trustStore.load(null, null);
            log.info("{} - Created empty truststore", tenant);
        }

        // Add custom certificates
        if (customCertificates != null && !customCertificates.isEmpty()) {
            // Get certificate info if available
            List<Certificate.CertificateInfo> certInfos = null;
            if (cert != null) {
                certInfos = cert.getCertificateInfoList();
            }

            for (int i = 0; i < customCertificates.size(); i++) {
                X509Certificate x509Cert = customCertificates.get(i);

                // Use cert info if available, otherwise use basic info
                String alias;
                if (certInfos != null && i < certInfos.size()) {
                    Certificate.CertificateInfo info = certInfos.get(i);
                    alias = String.format("custom-%s-%d",
                            info.getCertificateType().toLowerCase().replace(" ", "-"), i);

                    trustStore.setCertificateEntry(alias, x509Cert);

                    log.info("{} - Added certificate [{}] to truststore:", tenant, alias);
                    log.info("{}     Type: {}", tenant, info.getCertificateType());
                    log.info("{}     CN: {}", tenant, info.getCommonName());
                    log.info("{}     Serial: {}", tenant, info.getSerialNumber());
                    log.info("{}     Fingerprint (SHA-1): {}", tenant,
                            cert.getAllFingerprints().get(i));
                    log.info("{}     Fingerprint (SHA-256): {}", tenant,
                            cert.getAllFingerprints("SHA-256").get(i));
                } else {
                    // Fallback: use simple numbering and extract info from X509Certificate
                    alias = String.format("custom-cert-%d", i);
                    trustStore.setCertificateEntry(alias, x509Cert);

                    log.info("{} - Added certificate [{}] to truststore:", tenant, alias);
                    log.info("{}     Subject: {}", tenant, x509Cert.getSubjectX500Principal().getName());
                    log.info("{}     Issuer: {}", tenant, x509Cert.getIssuerX500Principal().getName());
                    log.info("{}     Serial: {}", tenant, x509Cert.getSerialNumber().toString(16).toUpperCase());
                }
            }

            log.info("{} - Final truststore contains {} total certificates ({} system + {} custom)",
                    tenant, trustStore.size(), systemCertCount, customCertificates.size());
        }

        return trustStore;
    }

    /**
     * Create TrustManagerFactory from KeyStore
     */
    public TrustManagerFactory createTrustManagerFactory(KeyStore trustStore) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        log.info("{} - TrustManagerFactory initialized with algorithm: {}",
                tenant, tmf.getAlgorithm());
        return tmf;
    }

    /**
     * Log chain structure
     */
    public void logChainStructure(Certificate cert) {
        if (!cert.isChain()) {
            return;
        }

        Certificate.CertificateInfo leaf = cert.getLeafCertificateInfo();
        Certificate.CertificateInfo root = cert.getRootCertificateInfo();
        List<Certificate.CertificateInfo> intermediates = cert.getIntermediateCertificates();

        log.info("{} - Certificate chain structure:", tenant);
        log.info("{}   Leaf: {}", tenant, leaf != null ? leaf.getCommonName() : "N/A");
        if (!intermediates.isEmpty()) {
            log.info("{}   Intermediates: {}", tenant,
                    intermediates.stream()
                            .map(Certificate.CertificateInfo::getCommonName)
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
        log.info("{}   Root: {}", tenant, root != null ? root.getCommonName() : "N/A");
    }

    /**
     * Create custom hostname verifier for MQTT.
     * Can be disabled via configuration property 'disableHostnameValidation'.
     */
    public HostnameVerifier createHostnameVerifier(ConnectorConfiguration connectorConfiguration) {
        // Check if hostname validation should be disabled
        Boolean disableHostnameValidation = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("disableHostnameValidation", false);

        if (disableHostnameValidation) {
            log.warn(
                    "{} - ⚠️  HOSTNAME VALIDATION DISABLED - This is insecure and should only be used for development/testing!",
                    tenant);
            return (hostname, session) -> {
                log.warn("{} - Accepting hostname without validation: {}", tenant, hostname);
                return true; // Accept any hostname
            };
        }

        // Normal hostname verification
        return (hostname, session) -> {
            log.info("{} - Hostname verification: requested={}", tenant, hostname);

            try {
                java.security.cert.Certificate[] peerCerts = session.getPeerCertificates();
                if (peerCerts.length > 0 && peerCerts[0] instanceof X509Certificate) {
                    X509Certificate cert = (X509Certificate) peerCerts[0];

                    String certCN = extractCN(cert.getSubjectX500Principal().getName());
                    log.info("{} -   Certificate CN: {}", tenant, certCN);

                    // Check Subject Alternative Names
                    Collection<List<?>> sans = cert.getSubjectAlternativeNames();
                    if (sans != null) {
                        for (List<?> san : sans) {
                            if (san.size() >= 2) {
                                String sanValue = san.get(1).toString();
                                log.info("{} -   SAN: {}", tenant, sanValue);

                                if (matchesHostname(hostname, sanValue)) {
                                    log.info("{} - Hostname verified via SAN", tenant);
                                    return true;
                                }
                            }
                        }
                    }

                    // Check CN
                    if (matchesHostname(hostname, certCN)) {
                        log.info("{} - Hostname verified via CN", tenant);
                        return true;
                    }
                }

                log.warn("{} - Hostname verification failed for: {}", tenant, hostname);
                return false;

            } catch (Exception e) {
                log.error("{} - Error during hostname verification", tenant, e);
                return false;
            }
        };
    }

    /**
     * Extract CN from Distinguished Name
     */
    private String extractCN(String dn) {
        if (dn == null)
            return null;

        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=")) {
                return trimmed.substring(3);
            }
        }
        return null;
    }

    /**
     * Check if hostname matches pattern (supports wildcards)
     */
    private boolean matchesHostname(String hostname, String pattern) {
        if (hostname == null || pattern == null) {
            return false;
        }

        // Exact match
        if (hostname.equalsIgnoreCase(pattern)) {
            return true;
        }

        // Wildcard match (e.g., *.isotopia.ca)
        if (pattern.startsWith("*.")) {
            String patternSuffix = pattern.substring(1);
            return hostname.endsWith(patternSuffix);
        }

        return false;
    }
}
