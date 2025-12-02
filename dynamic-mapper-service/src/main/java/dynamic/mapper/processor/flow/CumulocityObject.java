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
package dynamic.mapper.processor.flow;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;
import java.util.Map;

/**
 * A create/update to a Cumulocity domain object, sent to/from core or
 * IceFlow/offloading.
 * 
 * It's important to note these are used in both directions, so some fields that
 * are
 * always set when receiving may not be permitted to set when sending (e.g. for
 * an update).
 */
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CumulocityObject {

    /**
     * A payload similar to the WebSDK and/or to that used in the C8Y REST/SmartREST
     * API.
     * Main difference is ID handling - when providing an externalSource you don't
     * need to
     * provide an "id" in the payload as you would in those APIs.
     */
    private Object payload;

    /**
     * Which type in the C8Y api is being modified. Singular not plural. e.g.
     * "measurement".
     * The presence of this field also serves as a discriminator to identify this
     * object
     * as a Cumulocity object.
     */
    private CumulocityType cumulocityType;

    /** What kind of operation is being performed on this type */
    private String action; // "create" | "update"

    /**
     * Since we usually don't know the C8Y ID to put in the payload, the flow can
     * specify
     * a single external ID to lookup (and optionally create).
     * Mandatory to include one item when sending this (unless the internal C8Y "id"
     * is
     * known and passed in the payload, or it's an operation where there's a
     * dedicated field).
     * 
     * When a Cumulocity message (e.g. operation) is received, this will contain a
     * list of
     * ALL external ids for this Cumulocity device.
     */
    private List<ExternalId> externalSource;

    /**
     * For messages sent by the flow, this is "cumulocity" by default, but can be
     * set to
     * other options for other destinations.
     * For messages received by the flow this is not set.
     */
    private Destination destination;

    /**
     * Dictionary of contextData, contains additional properties for processing a
     * mapping
     * deviceName, deviceType: specify deviceName, deviceType for creating a new
     * device
     * processingMode: specify processing mode, either 'PERSISTENT', 'TRANSIENT'
     * attachmentName: specify name of attachment, when processing an EVENT with
     * attachment
     * attachmentType: specify type of attachment, when processing an EVENT with
     * attachment
     * attachmentData: specify data of attachment, when processing an EVENT with
     * attachment
     * 
     */
    private Map<String, String> contextData;
}