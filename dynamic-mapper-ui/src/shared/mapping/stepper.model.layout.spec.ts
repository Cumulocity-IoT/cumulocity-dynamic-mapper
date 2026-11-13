/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

import { Direction } from './mapping.model';
import { StepLayout, STEP_DEFINE_SUBSTITUTIONS, STEP_SELECT_TEMPLATES, STEP_TEST_MAPPING } from './stepper.model';

describe('StepLayout', () => {
  it('keeps the base order for inbound mappings', () => {
    const layout = StepLayout.for(Direction.INBOUND);
    expect(layout.steps).toEqual(['connector', 'general', 'templates', 'transformation', 'testing']);
    expect(layout.hasSubscription).toBe(false);
    expect(layout.subscription).toBe(-1);
    expect(layout.templates).toBe(STEP_SELECT_TEMPLATES);
    expect(layout.transformation).toBe(STEP_DEFINE_SUBSTITUTIONS);
    expect(layout.testing).toBe(STEP_TEST_MAPPING);
  });

  it('adds the Subscription section between general settings and templates for outbound', () => {
    const layout = StepLayout.for(Direction.OUTBOUND);
    expect(layout.steps).toEqual(['connector', 'general', 'subscription', 'templates', 'transformation', 'testing']);
    expect(layout.subscription).toBe(2);
    expect(layout.templates).toBe(3);
    expect(layout.transformation).toBe(4);
    expect(layout.testing).toBe(5);
  });

  it('treats a missing direction as inbound', () => {
    expect(StepLayout.for(undefined)).toBe(StepLayout.for(Direction.INBOUND));
  });

  it('maps a position back to its section', () => {
    expect(StepLayout.for(Direction.OUTBOUND).idAt(2)).toBe('subscription');
    expect(StepLayout.for(Direction.INBOUND).idAt(2)).toBe('templates');
  });

  it('translates advanceFromStepToEndStep, given in the base numbering, to the real position', () => {
    // 2 = templates in the base numbering (Java extension mappings advance from there)
    expect(StepLayout.for(Direction.INBOUND).fromBase(STEP_SELECT_TEMPLATES)).toBe(2);
    expect(StepLayout.for(Direction.OUTBOUND).fromBase(STEP_SELECT_TEMPLATES)).toBe(3);
    expect(StepLayout.for(Direction.OUTBOUND).fromBase(1)).toBe(1);
    expect(StepLayout.for(Direction.OUTBOUND).fromBase(undefined)).toBeUndefined();
  });
});
