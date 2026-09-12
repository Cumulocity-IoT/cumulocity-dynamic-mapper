/*
 * Copyright (c) 2025 Cumulocity GmbH
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
import { Injectable } from '@angular/core';
import { MappingType, TransformationType } from '../../shared';
import { CodeTemplate } from '../../configuration/shared/configuration.model';

/** Data captured in the Message Explorer's "Create mapping" flow, to be applied to the fresh
 *  mapping once the user lands on the mapping grid. */
export interface ExplorerMappingHandoff {
  sessionTopic?: string;
  topic: string;
  payload: string;
  key?: string;
  mappingType: MappingType;
  transformationType: TransformationType;
  codeTemplate?: CodeTemplate;
  generateSmartFunctionWithAI?: boolean;
  targetAPI?: string;
  publishTopic?: string;
  publishTopicSample?: string;
}

/**
 * Hands the mapping captured in Message Explorer's "Create mapping" flow off to the mapping
 * grid, across the `router.navigate` between them. Replaces a previous `history.state`-based
 * hand-off, which required manually clearing `history.state` after reading it (browser history
 * isn't cleared by Angular's router) to avoid replaying the same prefill on a later
 * back-navigation. A single-shot `consume()` here achieves the same thing without that
 * workaround and without depending on both routes being siblings under one parent.
 */
@Injectable({ providedIn: 'root' })
export class ExplorerMappingHandoffService {
  private pending: ExplorerMappingHandoff | null = null;

  set(data: ExplorerMappingHandoff): void {
    this.pending = data;
  }

  /** Returns the pending hand-off, if any, and clears it — so it is only ever applied once. */
  consume(): ExplorerMappingHandoff | null {
    const data = this.pending;
    this.pending = null;
    return data;
  }
}
