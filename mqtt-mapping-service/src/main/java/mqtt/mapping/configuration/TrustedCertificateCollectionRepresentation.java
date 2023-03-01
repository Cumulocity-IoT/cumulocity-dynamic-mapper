package mqtt.mapping.configuration;

import com.cumulocity.rest.representation.BaseCollectionRepresentation;
import java.util.Iterator;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.svenson.JSONTypeHint;

@AllArgsConstructor
@NoArgsConstructor
public class TrustedCertificateCollectionRepresentation extends BaseCollectionRepresentation<TrustedCertificateRepresentation> {

    @Getter(onMethod_ = @JSONTypeHint(TrustedCertificateRepresentation.class))
    private List<TrustedCertificateRepresentation> certificates;

    @JSONTypeHint(TrustedCertificateRepresentation.class)
    public void setCertificates(List<TrustedCertificateRepresentation> certificates) {
        this.certificates = certificates;
    }

    @Override
    public Iterator<TrustedCertificateRepresentation> iterator() {
        return certificates.iterator();
    }
}
