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
import { gettext } from '@c8y/ngx-components/gettext';
import { Qos } from './mapping.model';

/** Default applied when a mapping carries no explicit QoS — mirrors `Qos.DEFAULT` in the backend. */
export const QOS_DEFAULT = Qos.AT_LEAST_ONCE;

/**
 * Presentation metadata for the QoS levels, kept in one place so the mapping grid, the
 * properties step and any future view label a level identically. `level` is the numeric MQTT
 * QoS and defines the ordering: a connector that cannot honour a level falls back to the
 * strongest level below it.
 */
export const QOS_OPTIONS: ReadonlyArray<{
  value: Qos;
  label: string;
  level: number;
  description: string;
}> = [
  {
    value: Qos.AT_MOST_ONCE,
    label: gettext('At most once'),
    level: 0,
    description:
      gettext('Fire and forget. The message is acknowledged towards the broker immediately, before the mapping runs, so a processing failure loses it. Lowest latency.')
  },
  {
    value: Qos.AT_LEAST_ONCE,
    label: gettext('At least once'),
    level: 1,
    description:
      gettext('The message is acknowledged only after the mapping was processed successfully. A failure causes a redelivery, so the same message can be processed more than once.')
  },
  {
    value: Qos.EXACTLY_ONCE,
    label: gettext('Exactly once'),
    level: 2,
    description:
      gettext('As at least once, but the broker additionally suppresses duplicates. Only MQTT and Pulsar support this; other connectors fall back to at least once.')
  }
];

/** Label for a QoS level, falling back to the raw value for unknown input. */
export function qosLabel(qos: Qos | string): string {
  return QOS_OPTIONS.find((option) => option.value === qos)?.label ?? qos;
}

/** Numeric MQTT level of a QoS value; -1 for unknown input. */
export function qosLevel(qos: Qos | string): number {
  return QOS_OPTIONS.find((option) => option.value === qos)?.level ?? -1;
}

/**
 * Mirrors the backend's `Qos.clampTo`: the strongest supported level that is not stronger than
 * `requested`; if the connector supports nothing that weak, its weakest supported level (the
 * connector then over-delivers rather than running at a level it cannot implement).
 *
 * @param supported the connector's `supportedQos`; empty/undefined means "no restriction"
 */
export function clampQos(requested: Qos, supported?: Qos[]): Qos {
  if (!supported?.length || supported.includes(requested)) {
    return requested;
  }
  const requestedLevel = qosLevel(requested);
  const weaker = supported.filter((qos) => qosLevel(qos) < requestedLevel);
  const candidates = weaker.length ? weaker : supported;
  return candidates.reduce((best, qos) =>
    weaker.length
      ? qosLevel(qos) > qosLevel(best) ? qos : best
      : qosLevel(qos) < qosLevel(best) ? qos : best
  );
}
