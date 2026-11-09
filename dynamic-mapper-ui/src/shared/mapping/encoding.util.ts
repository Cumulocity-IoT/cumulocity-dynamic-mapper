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
import { Mapping } from './mapping.model';

// ─── Base64 / encoding utilities ──────────────────────────────────────────────

export function base64ToString(base64) {
  try {
    const binString = atob(base64);
    return new TextDecoder().decode(Uint8Array.from(binString, (m) => m.codePointAt(0)));
  } catch (error) {
    // Corrupt or truncated base64 (e.g. a damaged import) must not break the editor.
    console.warn('base64ToString: invalid base64 input, returning empty string', error);
    return '';
  }
}

/**
 * Serializes mappings for a JSON export file.
 * `id` (managed object id of this tenant) and `identifier` are left out: the import assigns
 * new values for both, so the exported values would never be used.
 */
export function serializeMappingsForExport(mappings: Mapping[]): string {
  const exported = mappings.map(({ id, identifier, ...rest }) => rest);
  return JSON.stringify(exported, undefined, 2);
}

export function stringToBase64(code2Encode) {
  const bytes = new TextEncoder().encode(code2Encode);
  const binString = Array.from(bytes, (byte: any) =>
    String.fromCodePoint(byte),
  ).join("");
  return btoa(binString);
}

export function base64ToBytes(base64) {
  const binString = atob(base64);
  return Uint8Array.from(binString, (m) => m.codePointAt(0));
}

export function bytesToBase64(bytes) {
  const binString = Array.from(bytes, (byte: any) =>
    String.fromCodePoint(byte),
  ).join("");
  return btoa(binString);
}
