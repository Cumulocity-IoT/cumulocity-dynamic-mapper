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

import { NgModule } from '@angular/core';
import { hookNavigator, hookRoute } from '@c8y/ngx-components';
import { featureResolver } from '../shared';
import { DocMainComponent } from './doc-main.component';
import { DocNavigationFactory } from './doc-navigation.factory';
import { DocOverviewComponent } from './doc-overview.component';
import { DocPageComponent } from './doc-page.component';
import { DocCodeTemplatesComponent } from './doc-code-templates.component';

/**
 * Documentation pages, grouped into the three tiers the navigator shows:
 * Start (what a new user needs first), Guides (task-oriented) and Reference (per-feature detail).
 *
 * Every page is a real route backed by one markdown file of the same name in public/docs.
 * Previously 16 of these were the *same* component scrolled to an anchor inside one ~1400-line
 * page, which is why a reader could never tell where a topic ended.
 *
 * Overview and Code Templates have their own components because they embed live Angular widgets
 * (tenant mapping/connector counts, and the code-template gallery). The rest are plain markdown.
 */
const MARKDOWN_PAGES = [
  // Start
  'quickstart-inbound', 'quickstart-outbound', 'concepts',
  // Guides
  'managing-connectors', 'connectors', 'define-mapping',
  'define-subscription-for-outbound', 'transformation-types',
  'jsonata', 'smartfunction', 'javaextension', 'custom-routing',
  'message-explorer', 'monitoring', 'troubleshooting',
  // Reference
  'payload-types', 'sparkplugb', 'flow-state', 'metadata',
  'reliability-settings', 'versioning', 'service-configuration',
  'access-control', 'ai-assisted'
];

/**
 * Routes that existed before the documentation was split into per-topic pages. Every old path
 * still resolves, so links in READMEs, community posts and bookmarks keep working. Paths not
 * listed here kept their name through the split and need no redirect.
 *
 * Note: a deep link carrying a *fragment* that has moved to another page (e.g.
 * `/introduction/overview#metadata`) lands on the right page but cannot scroll to the old
 * anchor, since the section now lives at `/introduction/metadata`.
 */
const LEGACY_REDIRECTS: Record<string, string> = {
  'getting-started': 'quickstart-inbound',
  // Renamed to 'quickstart-inbound' when the outbound quickstart was added, so the pair reads as a set.
  'quickstart': 'quickstart-inbound'
};

@NgModule({
  providers: [
    hookRoute({
      path: '',
      pathMatch: 'full',
      redirectTo: 'c8y-pkg-dynamic-mapper/introduction'
    }),
    hookRoute({
      path: 'c8y-pkg-dynamic-mapper/introduction',
      component: DocMainComponent,
      resolve: { feature: featureResolver },
      children: [
        { path: '', pathMatch: 'full', component: DocOverviewComponent, resolve: { feature: featureResolver } },
        { path: 'overview', component: DocOverviewComponent, resolve: { feature: featureResolver } },
        { path: 'code-templates', component: DocCodeTemplatesComponent },
        ...MARKDOWN_PAGES.map(page => ({ path: page, component: DocPageComponent })),
        ...Object.entries(LEGACY_REDIRECTS).map(([from, to]) => ({
          path: from, pathMatch: 'full' as const, redirectTo: to
        }))
      ]
    }),
    hookNavigator(DocNavigationFactory),
  ]
})
export class LandingModule { }
