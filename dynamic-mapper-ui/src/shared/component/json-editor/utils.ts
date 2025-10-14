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

import type { JSONPath } from 'immutable-json-patch'

const javaScriptPropertyRegex = /^[a-zA-Z$_][a-zA-Z$_\d]*$/
const integerNumberRegex = /^\d+$/

export function stringifyJSONPathCustom(path: JSONPath): string {
    return path
        .map((p, index) => {
            return integerNumberRegex.test(p)
                ? '[' + p + ']'
                : /[.[\]]/.test(p) || p === '' // match any character . or [ or ] and handle an empty string
                    ? (index > 0 ? '.' : '') + '"' + escapeQuotesCustom(p) + '"'
                    : (index > 0 ? '.' : '') + p
        })
        .join('')
}

function escapeQuotesCustom(prop: string): string {
    return prop.replace(/"/g, '\\"')
}

/**
* Parse a JSON path like 'items[3].name' or 'temp."high.value"' into a path array
*/
export function parseJSONPathCustom(pathStr: string): JSONPath {
    const path: JSONPath = []
    let i = 0

    while (i < pathStr.length) {
        // Handle dot notation
        if (pathStr[i] === '.') {
            i++

            // Check if the next character is a quote, indicating a property with special characters
            if (pathStr[i] === '"') {
                i++ // Move past the opening quote
                path.push(parseProp((c) => c === '"', true))
                eatCharacter('"') // Consume the closing quote
            } else {
                path.push(parseProp((c) => c === '.' || c === '['))
            }
        }
        // Handle bracket notation
        else if (pathStr[i] === '[') {
            i++

            if (pathStr[i] === '"') {
                i++
                path.push(parseProp((c) => c === '"', true))
                eatCharacter('"')
            } else {
                path.push(parseProp((c) => c === ']'))
            }

            eatCharacter(']')
        }
        // Handle the first segment (no leading dot)
        else {
            path.push(parseProp((c) => c === '.' || c === '['))
        }
    }

    function parseProp(isEnd: (char: string) => boolean, unescape = false) {
        let prop = ''

        while (i < pathStr.length && !isEnd(pathStr[i])) {
            if (unescape && pathStr[i] === '\\' && pathStr[i + 1] === '"') {
                // escaped double quote
                prop += '"'
                i += 2
            } else {
                prop += pathStr[i]
                i++
            }
        }

        return prop
    }

    function eatCharacter(char: string) {
        if (pathStr[i] !== char) {
            throw new SyntaxError(`Invalid JSON path: ${char} expected at position ${i}`)
        }
        i++
    }

    return path
}