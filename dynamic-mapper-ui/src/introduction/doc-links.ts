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

import { Router } from '@angular/router';

// Internal app routes (e.g. /c8y-pkg-dynamic-mapper/introduction/smartfunction) are intercepted
// so navigation goes through the Angular router instead of a full page reload; external links
// (target="_blank") pass through untouched.
const INTERNAL_LINK_PREFIX = '/c8y-pkg-dynamic-mapper/';

/** Offset (px) kept above a scrolled-to element so it clears the fixed action bar. */
export const DOC_SCROLL_OFFSET = 120;

export function scrollToDocElement(elementId: string): void {
  const element = document.getElementById(elementId);
  if (element) {
    window.scrollTo({ top: element.offsetTop - DOC_SCROLL_OFFSET, behavior: 'smooth' });
  }
}

/**
 * Shared click handling for rendered doc bodies. Same-page anchors (the {#id} heading ids) must
 * scroll rather than navigate: routing to "#id" would leave the doc route and reload the page.
 */
export function handleDocLinkClick(event: MouseEvent, router: Router): void {
  const target = (event.target as HTMLElement)?.closest('a');
  if (!target) return;
  const href = target.getAttribute('href');
  if (!href) return;
  if (href.startsWith('#')) {
    event.preventDefault();
    scrollToDocElement(href.slice(1));
    return;
  }
  if (!href.startsWith(INTERNAL_LINK_PREFIX)) return;
  event.preventDefault();
  router.navigateByUrl(href);
}
