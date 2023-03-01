package mqtt.mapping.configuration;



import com.cumulocity.model.DateTimeConverter;
import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import lombok.*;
import static lombok.EqualsAndHashCode.*;
import org.joda.time.DateTime;
import org.svenson.converter.JSONConverter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class TrustedCertificateRepresentation extends AbstractExtensibleRepresentation {
    private int version;
    @Include
    private String fingerprint;
    private String name;
    private String status;
    private Boolean autoRegistrationEnabled;
    private String serialNumber;
    private String algorithmName;
    private String issuer;
    private String subject;
    private DateTime notBefore;
    private DateTime notAfter;
    private Boolean proofOfPossessionValid;
    private String proofOfPossessionUnsignedVerificationCode;
    private DateTime proofOfPossessionVerificationCodeUsableUntil;
    @Include
    private String certInPemFormat;

    @JSONConverter(type = DateTimeConverter.class)
    public DateTime getNotBefore() {
        return notBefore;
    }

    @JSONConverter(type = DateTimeConverter.class)
    public DateTime getNotAfter() {
        return notAfter;
    }

    @JSONConverter(type = DateTimeConverter.class)
    public DateTime getProofOfPossessionVerificationCodeUsableUntil() { return proofOfPossessionVerificationCodeUsableUntil; }
}


