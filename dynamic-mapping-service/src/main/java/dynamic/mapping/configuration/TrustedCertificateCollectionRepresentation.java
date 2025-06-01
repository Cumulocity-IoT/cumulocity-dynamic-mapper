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

package dynamic.mapping.configuration;

import com.cumulocity.rest.representation.BaseCollectionRepresentation;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.svenson.JSONTypeHint;

import java.util.Iterator;
import java.util.List;

// @AllArgsConstructor
// @NoArgsConstructor
// public class TrustedCertificateCollectionRepresentation extends BaseCollectionRepresentation<TrustedCertificateRepresentation> {

//     @Getter(onMethod_ = @JSONTypeHint(TrustedCertificateRepresentation.class))
//     private List<TrustedCertificateRepresentation> certificates;

//     @JSONTypeHint(TrustedCertificateRepresentation.class)
//     public void setCertificates(List<TrustedCertificateRepresentation> certificates) {
//         this.certificates = certificates;
//     }

//     @Override
//     public Iterator<TrustedCertificateRepresentation> iterator() {
//         return certificates.iterator();
//     }
// }

@AllArgsConstructor
@NoArgsConstructor
public class TrustedCertificateCollectionRepresentation extends BaseCollectionRepresentation<TrustedCertificateRepresentation> {

    private List<TrustedCertificateRepresentation> certificates;

    @JSONTypeHint(TrustedCertificateRepresentation.class)
    public List<TrustedCertificateRepresentation> getCertificates() {
        return certificates;
    }

    @JSONTypeHint(TrustedCertificateRepresentation.class)
    public void setCertificates(List<TrustedCertificateRepresentation> certificates) {
        this.certificates = certificates;
    }

    @Override
    public Iterator<TrustedCertificateRepresentation> iterator() {
        return certificates.iterator();
    }
}
