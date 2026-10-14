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
let _seed = Date.now();

export function valueOrDefault<T>(value: T | undefined, defaultValue: T) {
  return typeof value === 'undefined' ? defaultValue : value;
}

export function srand(seed) {
  _seed = seed;
}

export function rand(min, max) {
  const minx = valueOrDefault(min, 0);
  const maxx = valueOrDefault(max, 0);
  _seed = (_seed * 9301 + 49297) % 233280;
  return minx + (_seed / 233280) * (maxx - minx);
}

export function numbers(config) {
  const cfg = config || {};
  const min = valueOrDefault(cfg.min, 0);
  const max = valueOrDefault(cfg.max, 100);
  const from = valueOrDefault(cfg.from, []);
  const count = valueOrDefault(cfg.count, 8);
  const decimals = valueOrDefault(cfg.decimals, 8);
  const continuity = valueOrDefault(cfg.continuity, 1);
  const dfactor = Math.pow(10, decimals) || 0;
  const data = [];
  let i, value;

  for (i = 0; i < count; ++i) {
    value = (from[i] || 0) + rand(min, max);
    if (rand(0, 0) <= continuity) {
      data.push(Math.round(dfactor * value) / dfactor);
    } else {
      data.push(null);
    }
  }

  return data;
}

export function labels(config) {
  const cfg = config || {};
  const min = cfg.min || 0;
  const max = cfg.max || 100;
  const count = cfg.count || 8;
  const step = (max - min) / count;
  const decimals = cfg.decimals || 8;
  const dfactor = Math.pow(10, decimals) || 0;
  const prefix = cfg.prefix || '';
  const values = [];
  let i;

  for (i = min; i < max; i += step) {
    values.push(prefix + Math.round(dfactor * i) / dfactor);
  }

  return values;
}