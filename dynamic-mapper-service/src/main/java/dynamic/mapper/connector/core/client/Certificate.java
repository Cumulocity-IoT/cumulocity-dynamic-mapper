package dynamic.mapper.connector.core.client;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class Certificate {
    private String fingerprint;
    private String certInPemFormat;

    /**
     * Constructor with both fingerprint and PEM
     */
    public Certificate(String fingerprint, String certInPemFormat) {
        this.fingerprint = fingerprint;
        this.certInPemFormat = normalizePemFormat(certInPemFormat);
    }

    /**
     * Constructor for certificate without fingerprint
     * Fingerprint will be calculated lazily
     */
    public Certificate(String certInPemFormat) {
        this.certInPemFormat = normalizePemFormat(certInPemFormat);
        this.fingerprint = null; // Will be calculated lazily
    }

    /**
     * Static factory method to create from PEM
     */
    public static Certificate fromPem(String pem) {
        return new Certificate(pem);
    }

    /**
     * Static factory method to create from X509Certificate
     */
    public static Certificate fromX509(X509Certificate cert) throws Exception {
        String pem = convertX509ToPem(cert);
        return new Certificate(pem);
    }

    /**
     * Static factory method to create from multiple certificates (chain)
     */
    public static Certificate fromChain(X509Certificate... certificates) throws Exception {
        StringBuilder pemChain = new StringBuilder();
        for (X509Certificate cert : certificates) {
            pemChain.append(convertX509ToPem(cert)).append("\n");
        }
        return new Certificate(pemChain.toString());
    }

    /**
     * Static factory method to create from PEM file path
     */
    public static Certificate fromFile(String filePath) throws Exception {
        String pem = java.nio.file.Files.readString(
                java.nio.file.Paths.get(filePath), StandardCharsets.UTF_8);
        return new Certificate(pem);
    }

    // ========== FINGERPRINT METHODS ==========

    /**
     * Get SHA-1 fingerprint, calculating it lazily if needed
     */
    public String getFingerprint() {
        if (fingerprint == null && certInPemFormat != null) {
            fingerprint = calculateFingerprint("SHA-1");
        }
        return fingerprint;
    }

    /**
     * Calculate SHA-1 fingerprint from the first certificate in the chain
     */
    private String calculateFingerprint(String algorithm) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            ByteArrayInputStream is = new ByteArrayInputStream(
                    certInPemFormat.getBytes(StandardCharsets.UTF_8));
            X509Certificate cert = (X509Certificate) cf.generateCertificate(is);

            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] digest = md.digest(cert.getEncoded());

            return formatFingerprint(digest);
        } catch (Exception e) {
            log.error("Error calculating {} fingerprint", algorithm, e);
            return "UNKNOWN";
        }
    }

    /**
     * Get SHA-1 fingerprint (alias for getFingerprint)
     */
    public String getSHA1Fingerprint() {
        return getFingerprint();
    }

    /**
     * Get SHA-256 fingerprint
     */
    public String getSHA256Fingerprint() {
        return calculateFingerprint("SHA-256");
    }

    /**
     * Get MD5 fingerprint (legacy, not recommended)
     */
    public String getMD5Fingerprint() {
        return calculateFingerprint("MD5");
    }

    /**
     * Get all fingerprints for all certificates in the chain
     */
    public List<String> getAllFingerprints() {
        return getAllFingerprints("SHA-1");
    }

    /**
     * Get all fingerprints for all certificates in the chain using specified
     * algorithm
     */
    public List<String> getAllFingerprints(String algorithm) {
        List<String> fingerprints = new ArrayList<>();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            ByteArrayInputStream is = new ByteArrayInputStream(
                    certInPemFormat.getBytes(StandardCharsets.UTF_8));

            Collection<? extends java.security.cert.Certificate> certs = cf.generateCertificates(is);

            MessageDigest md = MessageDigest.getInstance(algorithm);

            for (java.security.cert.Certificate cert : certs) {
                if (cert instanceof X509Certificate) {
                    md.reset();
                    byte[] digest = md.digest(cert.getEncoded());
                    fingerprints.add(formatFingerprint(digest));
                }
            }
        } catch (Exception e) {
            log.error("Error calculating fingerprints", e);
        }
        return fingerprints;
    }

    /**
     * Format byte array as colon-separated hex string
     */
    private String formatFingerprint(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            if (i > 0)
                sb.append(":");
            sb.append(String.format("%02X", digest[i]));
        }
        return sb.toString();
    }

    // ========== CHAIN METHODS ==========

    /**
     * Get all certificates in the chain as separate PEM strings
     */
    public List<String> getCertificatesInChain() {
        List<String> certs = new ArrayList<>();
        if (certInPemFormat == null)
            return certs;

        String[] parts = certInPemFormat.split("-----END CERTIFICATE-----");
        for (String part : parts) {
            if (part.trim().isEmpty())
                continue;

            String cert = part.trim();
            if (!cert.startsWith("-----BEGIN CERTIFICATE-----")) {
                cert = "-----BEGIN CERTIFICATE-----\n" + cert;
            }
            cert = cert + "\n-----END CERTIFICATE-----";
            certs.add(cert);
        }
        return certs;
    }

    /**
     * Get all X509Certificate objects in the chain
     */
    public List<X509Certificate> getX509Certificates() {
        List<X509Certificate> certificates = new ArrayList<>();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            ByteArrayInputStream is = new ByteArrayInputStream(
                    certInPemFormat.getBytes(StandardCharsets.UTF_8));

            Collection<? extends java.security.cert.Certificate> certs = cf.generateCertificates(is);

            for (java.security.cert.Certificate cert : certs) {
                if (cert instanceof X509Certificate) {
                    certificates.add((X509Certificate) cert);
                }
            }
        } catch (Exception e) {
            log.error("Error parsing X509 certificates", e);
        }
        return certificates;
    }

    /**
     * Check if this contains a certificate chain (multiple certificates)
     */
    public boolean isChain() {
        if (certInPemFormat == null)
            return false;
        return getCertificateCount() > 1;
    }

    /**
     * Get the number of certificates in the chain
     */
    public int getCertificateCount() {
        if (certInPemFormat == null)
            return 0;
        int count = 0;
        int index = 0;
        while ((index = certInPemFormat.indexOf("-----BEGIN CERTIFICATE-----", index)) != -1) {
            count++;
            index += 27;
        }
        return count;
    }

    // ========== CERTIFICATE INFO METHODS ==========

    /**
     * Get detailed information about all certificates in the chain
     */
    public List<CertificateInfo> getCertificateInfoList() {
        List<CertificateInfo> infoList = new ArrayList<>();
        List<X509Certificate> certs = getX509Certificates();

        for (int i = 0; i < certs.size(); i++) {
            X509Certificate cert = certs.get(i);
            infoList.add(new CertificateInfo(cert, i));
        }

        return infoList;
    }

    /**
     * Get information about the first certificate (leaf certificate)
     */
    public CertificateInfo getLeafCertificateInfo() {
        List<CertificateInfo> infos = getCertificateInfoList();
        return infos.isEmpty() ? null : infos.get(0);
    }

    /**
     * Get information about the root certificate
     */
    public CertificateInfo getRootCertificateInfo() {
        List<CertificateInfo> infos = getCertificateInfoList();
        if (infos.isEmpty())
            return null;

        // Find the self-signed certificate (root)
        for (CertificateInfo info : infos) {
            if (info.isSelfSigned()) {
                return info;
            }
        }

        // If no self-signed cert found, return the last one
        return infos.get(infos.size() - 1);
    }

    /**
     * Get all intermediate certificates (not leaf, not root)
     */
    public List<CertificateInfo> getIntermediateCertificates() {
        List<CertificateInfo> infos = getCertificateInfoList();
        if (infos.size() <= 1)
            return Collections.emptyList();

        List<CertificateInfo> intermediates = new ArrayList<>();
        for (int i = 1; i < infos.size(); i++) {
            CertificateInfo info = infos.get(i);
            if (!info.isSelfSigned()) {
                intermediates.add(info);
            }
        }
        return intermediates;
    }

    // ========== VALIDATION METHODS ==========

    /**
     * Check if all certificates in the chain are valid (not expired)
     */
    public boolean isValid() {
        List<X509Certificate> certs = getX509Certificates();
        for (X509Certificate cert : certs) {
            try {
                cert.checkValidity();
            } catch (Exception e) {
                return false;
            }
        }
        return !certs.isEmpty();
    }

    /**
     * Check if all certificates will be valid at a specific date
     */
    public boolean isValidAt(Date date) {
        List<X509Certificate> certs = getX509Certificates();
        for (X509Certificate cert : certs) {
            try {
                cert.checkValidity(date);
            } catch (Exception e) {
                return false;
            }
        }
        return !certs.isEmpty();
    }

    /**
     * Get validation errors for all certificates
     */
    public List<String> getValidationErrors() {
        List<String> errors = new ArrayList<>();
        List<CertificateInfo> infos = getCertificateInfoList();

        for (CertificateInfo info : infos) {
            try {
                info.getCertificate().checkValidity();
            } catch (Exception e) {
                errors.add(String.format("Certificate %d (%s): %s",
                        info.getIndex(), info.getCommonName(), e.getMessage()));
            }
        }

        return errors;
    }

    /**
     * Check if the certificate chain is properly ordered
     */
    public boolean isChainOrdered() {
        List<X509Certificate> certs = getX509Certificates();
        if (certs.size() <= 1)
            return true;

        for (int i = 0; i < certs.size() - 1; i++) {
            X509Certificate current = certs.get(i);
            X509Certificate next = certs.get(i + 1);

            // Check if current cert is signed by next cert
            if (!current.getIssuerX500Principal().equals(next.getSubjectX500Principal())) {
                return false;
            }
        }

        return true;
    }

    /**
     * Verify the certificate chain
     */
    public boolean verifyChain() {
        List<X509Certificate> certs = getX509Certificates();
        if (certs.isEmpty())
            return false;

        try {
            for (int i = 0; i < certs.size() - 1; i++) {
                X509Certificate current = certs.get(i);
                X509Certificate issuer = certs.get(i + 1);

                // Verify signature
                current.verify(issuer.getPublicKey());
            }

            // Verify root is self-signed
            X509Certificate root = certs.get(certs.size() - 1);
            root.verify(root.getPublicKey());

            return true;
        } catch (Exception e) {
            log.error("Chain verification failed", e);
            return false;
        }
    }

    // ========== UTILITY METHODS ==========

    /**
     * Normalize PEM format - ensures proper line breaks
     */
    private static String normalizePemFormat(String pem) {
        if (pem == null || pem.isEmpty()) {
            return pem;
        }

        // If already properly formatted, return as-is
        if (pem.contains("\n") && pem
                .matches("(?s).*-----BEGIN CERTIFICATE-----\\s+[A-Za-z0-9+/=\\s]+-----END CERTIFICATE-----.*")) {
            return pem.trim();
        }

        StringBuilder result = new StringBuilder();
        String[] parts = pem.split("-----END CERTIFICATE-----");

        for (String part : parts) {
            if (!part.contains("-----BEGIN CERTIFICATE-----")) {
                continue;
            }

            String[] certParts = part.split("-----BEGIN CERTIFICATE-----", 2);
            if (certParts.length < 2) {
                continue;
            }

            result.append("-----BEGIN CERTIFICATE-----\n");

            // Clean and reformat base64 content
            String base64 = certParts[1].replaceAll("\\s+", "");

            // Add line breaks every 64 characters
            for (int i = 0; i < base64.length(); i += 64) {
                int end = Math.min(i + 64, base64.length());
                result.append(base64.substring(i, end)).append("\n");
            }

            result.append("-----END CERTIFICATE-----\n");
        }

        return result.toString().trim();
    }

    /**
     * Convert X509Certificate to PEM format
     */
    private static String convertX509ToPem(X509Certificate cert) throws Exception {
        Base64.Encoder encoder = Base64.getEncoder();
        byte[] certBytes = cert.getEncoded();
        String base64 = encoder.encodeToString(certBytes);

        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN CERTIFICATE-----\n");

        // Break into 64-character lines
        for (int i = 0; i < base64.length(); i += 64) {
            int end = Math.min(i + 64, base64.length());
            pem.append(base64.substring(i, end)).append("\n");
        }

        pem.append("-----END CERTIFICATE-----");

        return pem.toString();
    }

    /**
     * Export to file
     */
    public void exportToFile(String filePath) throws Exception {
        java.nio.file.Files.writeString(
                java.nio.file.Paths.get(filePath),
                certInPemFormat,
                StandardCharsets.UTF_8);
    }

    /**
     * Get a pretty-printed summary of the certificate chain
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Certificate Chain Summary:\n");
        sb.append("=========================\n");
        sb.append(String.format("Total Certificates: %d\n", getCertificateCount()));
        sb.append(String.format("Is Chain: %s\n", isChain()));
        sb.append(String.format("Is Valid: %s\n", isValid()));
        sb.append(String.format("Chain Ordered: %s\n", isChainOrdered()));
        sb.append("\n");

        List<CertificateInfo> infos = getCertificateInfoList();
        for (CertificateInfo info : infos) {
            sb.append(info.toString()).append("\n\n");
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("Certificate(count=%d, valid=%s, fingerprint=%s)",
                getCertificateCount(), isValid(), getFingerprint());
    }

    // ========== INNER CLASS: CertificateInfo ==========

    @Data
    public static class CertificateInfo {
        private final X509Certificate certificate;
        private final int index;

        public String getCommonName() {
            String dn = certificate.getSubjectX500Principal().getName();
            return extractCN(dn);
        }

        public String getIssuerCommonName() {
            String dn = certificate.getIssuerX500Principal().getName();
            return extractCN(dn);
        }

        private String extractCN(String dn) {
            for (String part : dn.split(",")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("CN=")) {
                    return trimmed.substring(3);
                }
            }
            return dn;
        }

        public String getOrganization() {
            String dn = certificate.getSubjectX500Principal().getName();
            return extractField(dn, "O=");
        }

        public String getCountry() {
            String dn = certificate.getSubjectX500Principal().getName();
            return extractField(dn, "C=");
        }

        private String extractField(String dn, String prefix) {
            for (String part : dn.split(",")) {
                String trimmed = part.trim();
                if (trimmed.startsWith(prefix)) {
                    return trimmed.substring(prefix.length());
                }
            }
            return null;
        }

        public String getSerialNumber() {
            return certificate.getSerialNumber().toString(16).toUpperCase();
        }

        public Date getNotBefore() {
            return certificate.getNotBefore();
        }

        public Date getNotAfter() {
            return certificate.getNotAfter();
        }

        public boolean isSelfSigned() {
            return certificate.getSubjectX500Principal()
                    .equals(certificate.getIssuerX500Principal());
        }

        public boolean isCA() {
            return certificate.getBasicConstraints() >= 0;
        }

        public boolean isValid() {
            try {
                certificate.checkValidity();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        public String getSignatureAlgorithm() {
            return certificate.getSigAlgName();
        }

        public String getPublicKeyAlgorithm() {
            return certificate.getPublicKey().getAlgorithm();
        }

        public int getPublicKeySize() {
            if (certificate.getPublicKey() instanceof java.security.interfaces.RSAPublicKey) {
                return ((java.security.interfaces.RSAPublicKey) certificate.getPublicKey())
                        .getModulus().bitLength();
            }
            return -1;
        }

        public List<String> getSubjectAlternativeNames() {
            List<String> sans = new ArrayList<>();
            try {
                Collection<List<?>> altNames = certificate.getSubjectAlternativeNames();
                if (altNames != null) {
                    for (List<?> altName : altNames) {
                        if (altName.size() >= 2) {
                            sans.add(altName.get(1).toString());
                        }
                    }
                }
            } catch (Exception e) {
                // No SANs
            }
            return sans;
        }

        public String getCertificateType() {
            if (isSelfSigned()) {
                return "Root CA";
            } else if (isCA()) {
                return "Intermediate CA";
            } else {
                return "End Entity (Leaf)";
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Certificate [%d] - %s:\n", index, getCertificateType()));
            sb.append(String.format("  Subject CN:  %s\n", getCommonName()));
            sb.append(String.format("  Issuer CN:   %s\n", getIssuerCommonName()));
            sb.append(String.format("  Serial:      %s\n", getSerialNumber()));
            sb.append(String.format("  Valid From:  %s\n", getNotBefore()));
            sb.append(String.format("  Valid Until: %s\n", getNotAfter()));
            sb.append(String.format("  Is Valid:    %s\n", isValid()));
            sb.append(String.format("  Self-Signed: %s\n", isSelfSigned()));
            sb.append(String.format("  Is CA:       %s\n", isCA()));
            sb.append(String.format("  Signature:   %s\n", getSignatureAlgorithm()));
            sb.append(String.format("  Public Key:  %s (%d bits)\n",
                    getPublicKeyAlgorithm(), getPublicKeySize()));

            List<String> sans = getSubjectAlternativeNames();
            if (!sans.isEmpty()) {
                sb.append(String.format("  SANs:        %s\n", String.join(", ", sans)));
            }

            return sb.toString();
        }
    }
}