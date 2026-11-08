/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/** A random six-digit identifier as a string. */
export function randomIdAsString(): string {
  return Math.floor(100000 + Math.random() * 900000).toString();
}
