/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

package dynamic.mapper.configuration;


import com.cumulocity.model.DateTimeConverter;
import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import lombok.*;
import org.joda.time.DateTime;
import org.svenson.converter.JSONConverter;

import static lombok.EqualsAndHashCode.Include;

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


