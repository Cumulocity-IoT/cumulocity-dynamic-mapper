/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

/**
 * Ambient declarations for the globals the GraalVM JavaScript runtime injects into Smart
 * Functions. They are not part of the Smart Function API surface itself, but templates and user
 * code legitimately use them, so the template type-check (`npm run check:templates`) needs to
 * know they exist.
 */

/**
 * GraalVM polyglot access to Java classes.
 *
 * Note the fragility this represents: the fully-qualified name is a **string**, so moving or
 * renaming a Java class compiles cleanly and fails at runtime, and the class must additionally be
 * on the host-access allow-list. Prefer the Smart Function API over `Java.type` where one exists.
 */
declare const Java: {
  /** Resolves a Java class by fully-qualified name, e.g. `Java.type('java.util.HashMap')`. */
  type(fullyQualifiedName: string): any;
};

/** Base64-decode. Provided by the runtime; see the binary-helpers section of the Smart Function docs. */
declare function atob(encoded: string): string;

/** Base64-encode. Provided by the runtime. */
declare function btoa(raw: string): string;
