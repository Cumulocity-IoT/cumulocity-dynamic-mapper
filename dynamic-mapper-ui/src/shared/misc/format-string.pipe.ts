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
import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'formatStringAsWords',
  standalone: true
})
export class FormatStringPipe implements PipeTransform {
  transform(value: string, removeLastWords: number = 0): string {
    if (!value) return '';

    // First, replace underscores with spaces
    const withSpaces = value.replace(/_/g, ' ');

    // Convert to lowercase and split into words
    const words = withSpaces.toLowerCase().split(' ');

    // Remove last X words if specified
    const trimmedWords = (removeLastWords > 0 && words.length > removeLastWords)
      ? words.slice(0, -removeLastWords)
      : words;

    // Capitalize the first letter of each word
    const capitalizedWords = trimmedWords.map(word =>
      word.charAt(0).toUpperCase() + word.slice(1)
    );

    // Join the words back together
    return capitalizedWords.join(' ');
  }
}