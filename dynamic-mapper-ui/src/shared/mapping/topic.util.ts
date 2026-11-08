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
import { TOPIC_WILDCARD_MULTI, TOPIC_WILDCARD_SINGLE } from './util-constants';

// ─── Topic string utilities ───────────────────────────────────────────────────

export function splitTopicExcludingSeparator(topic: string, cutOffLeadingSlash: boolean): string[] {
  if (topic) {
    let topix = topic.trim();

    if (cutOffLeadingSlash) {
      // Original behavior: remove both leading and trailing slashes
      topix = topix.replace(/(\/{1,}$)|(^\/{1,})/g, '');
      return topix.split(/\//g);
    } else {
      // New behavior: keep leading slash, remove only trailing slashes
      topix = topix.replace(/\/{1,}$/g, '');
      if (topix.startsWith('//')) {
        topix = '/' + topix.replace(/^\/+/, '');
      }

      if (topix.startsWith('/')) {
        const parts = topix.substring(1).split(/\//g);
        return ['/'].concat(parts);
      }

      return topix.split(/\//g);
    }
  } else return undefined;
}

export function splitTopicIncludingSeparator(topic: string): string[] {
  const topix = topic;
  return topix.split(/(?<=\/)|(?=\/)/g);
}

export function normalizeTopic(topic: string) {
  let topix = topic;
  if (topix == undefined) topix = '';
  // reduce multiple leading or trailing "/" to just one "/"
  let nt = topix.trim().replace(/(\/{2,}$)|(^\/{2,})/g, '/');
  // do not use starting slashes, see as well https://www.hivemq.com/blog/mqtt-essentials-part-5-mqtt-topics-best-practices/
  // remove trailing "/" if topic is ends with "#"
  nt = nt.replace(/(#\/$)/g, '#');
  return nt;
}

export function deriveSampleTopicFromTopic(topic: string) {
  let topix = topic;
  if (topix == undefined) topix = '';
  topix = normalizeTopic(topix);
  // replace trailing TOPIC_WILDCARD_MULTI "#" with TOPIC_WILDCARD_SINGLE "*"
  const nt = topix.trim().replace(/#+$/, '+');
  return nt;
}

/**
 * Checks whether `sample` still has the same level structure as `topic`,
 * i.e. same number of levels (a trailing "#" in topic matches any number of
 * remaining levels) and identical fixed (non-wildcard) segments. Used to
 * decide whether an existing sample can be kept when the topic changes,
 * instead of always deriving a fresh sample from the topic.
 */
export function topicsHaveSameStructure(topic: string, sample: string): boolean {
  if (!topic || !sample) return false;

  const splitTopic = splitTopicExcludingSeparator(topic, false);
  const splitSample = splitTopicExcludingSeparator(sample, false);
  if (!splitTopic || !splitSample) return false;

  const hasMultiWildcard = splitTopic[splitTopic.length - 1] === TOPIC_WILDCARD_MULTI;
  const fixedTopic = hasMultiWildcard ? splitTopic.slice(0, -1) : splitTopic;

  if (hasMultiWildcard ? splitSample.length < fixedTopic.length : splitSample.length !== fixedTopic.length) {
    return false;
  }

  for (let i = 0; i < fixedTopic.length; i++) {
    if ('/' === fixedTopic[i] && '/' !== splitSample[i]) return false;
    if ('/' === splitSample[i] && '/' !== fixedTopic[i]) return false;
    if (
      fixedTopic[i] !== '/' &&
      fixedTopic[i] !== TOPIC_WILDCARD_SINGLE &&
      fixedTopic[i] !== TOPIC_WILDCARD_MULTI &&
      fixedTopic[i] !== splitSample[i]
    ) {
      return false;
    }
  }
  return true;
}

export function isWildcardTopic(topic: string): boolean {
  const result =
    topic.includes(TOPIC_WILDCARD_MULTI) ||
    topic.includes(TOPIC_WILDCARD_SINGLE);
  return result;
}
