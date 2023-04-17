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
 * @authors Christof Strack
 */
import {
  Mapping,
  MappingType,
  RepairStrategy,
} from "../../shared/mapping.model";

export interface C8YRequest {
  predecessor?: number;
  method?: string;
  source?: any;
  externalIdType?: string;
  request?: any;
  response?: any;
  targetAPI?: string;
  error?: string;
}

export interface ProcessingContext {
  mapping: Mapping;
  topic: string;
  resolvedPublishTopic?: string;
  payload?: JSON;
  payloadRaw?: any;
  requests?: C8YRequest[];
  errors?: string[];
  processingType?: ProcessingType;
  cardinality: Map<string, number>;
  mappingType: MappingType;
  postProcessingCache: Map<string, SubstituteValue[]>;
  sendPayload?: boolean;
}

export enum ProcessingType {
  UNDEFINED,
  ONE_DEVICE_ONE_VALUE,
  ONE_DEVICE_MULTIPLE_VALUE,
  MULTIPLE_DEVICE_ONE_VALUE,
  MULTIPLE_DEVICE_MULTIPLE_VALUE,
}

export enum SubstituteValueType {
  NUMBER,
  TEXTUAL,
  OBJECT,
  IGNORE,
  ARRAY,
}

export interface SubstituteValue {
  value: any;
  type: SubstituteValueType;
  repairStrategy: RepairStrategy;
}
