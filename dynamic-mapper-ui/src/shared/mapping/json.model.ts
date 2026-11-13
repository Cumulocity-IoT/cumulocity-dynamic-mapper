/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/** Any value JSON can hold. */
export type JsonValue = string | number | boolean | null | JsonValue[] | JsonObject;

export interface JsonObject {
  [key: string]: JsonValue;
}

/**
 * A parsed source or target template. Normally an object; a payload that is not JSON-shaped as an object
 * (an array, or text) is possible, so code that reads keys narrows with {@link isJsonObject} first.
 */
export type Template = JsonValue;

/** A JSON schema handed to the template editor for completion and validation. */
export type JsonSchema = JsonObject;

/** The options this application passes to the JSON editor; the editor accepts further library options. */
export interface JsonEditorOptions {
  mode?: 'tree' | 'text' | 'table' | 'view';
  removeModes?: string[];
  mainMenuBar?: boolean;
  navigationBar?: boolean;
  statusBar?: boolean;
  readOnly?: boolean;
  name?: string;
  [option: string]: unknown;
}

/** True for a plain JSON object (not an array, not null, not a primitive). */
export function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
