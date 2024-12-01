/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.processor.model;

public enum MappingType {
    JSON ("JSON", String.class),
    FLAT_FILE ( "FLAT_FILE", String.class),
    GENERIC_BINARY ( "GENERIC_BINARY", byte[].class),
    PROTOBUF_STATIC ( "PROTOBUF_STATIC", byte[].class),
    PROCESSOR_EXTENSION_SOURCE ( "PROCESSOR_EXTENSION_SOURCE", byte[].class),
    PROCESSOR_EXTENSION_SOURCE_TARGET ( "PROCESSOR_EXTENSION_SOURCE_TARGET", byte[].class);

    public final String name;
    public final Class<?> payloadType;

    private MappingType (String name, Class<?> payloadType){
        this.name = name;
        this.payloadType = payloadType;
    }

    public String getName() {
        return this.name;
    }

    public Class<?> getPayloadType() {
        return this.payloadType;
    }
}
