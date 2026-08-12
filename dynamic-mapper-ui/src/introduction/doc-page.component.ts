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

import { Component, ElementRef, HostListener, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
import { ActivatedRoute, Router } from '@angular/router';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { DocMarkdownService } from './doc-markdown.service';

@Component({
  selector: 'd11r-doc-page',
  templateUrl: './doc-page.component.html',
  styleUrls: ['./doc-shared.css'],
  standalone: true,
  imports: [CoreModule],
  // The body is rendered markdown injected via [innerHTML], which never receives the
  // _ngcontent-* attribute Angular's default (emulated) encapsulation scopes styles with —
  // so styleUrls would silently never apply to it. doc-shared.css is already a shared,
  // unscoped stylesheet (every doc page component includes the same file), so this is safe.
  encapsulation: ViewEncapsulation.None
})
export class DocPageComponent implements OnInit {
  @ViewChild('docBody', { static: false }) docBodyRef: ElementRef<HTMLElement>;

  title = '';
  html: SafeHtml = '';

  // Internal app routes (e.g. /c8y-pkg-dynamic-mapper/introduction/smartfunction) are
  // intercepted below so navigation goes through the Angular router instead of a full
  // page reload; external links (target="_blank") pass through untouched.
  private static readonly INTERNAL_LINK_PREFIX = '/c8y-pkg-dynamic-mapper/';

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private markdownService: DocMarkdownService,
    private sanitizer: DomSanitizer
  ) {}

  async ngOnInit(): Promise<void> {
    const docPath: string = this.route.snapshot.routeConfig?.path || this.route.snapshot.data['docPath'];
    try {
      const rendered = await this.markdownService.loadAndRender(docPath);
      this.title = rendered.title;
      this.html = this.sanitizer.bypassSecurityTrustHtml(rendered.html);
    } catch (error) {
      console.error(error);
      this.title = 'Documentation unavailable';
      this.html = this.sanitizer.bypassSecurityTrustHtml(
        '<div class="admonition caution"><div class="title">Documentation unavailable</div>' +
        '<div class="content">This page could not be loaded. Please try again later.</div></div>'
      );
    }
  }

  scrollToElement(elementId: string): void {
    const element = document.getElementById(elementId);
    if (element) {
      window.scrollTo({ top: element.offsetTop - 80, behavior: 'smooth' });
    }
  }

  @HostListener('click', ['$event'])
  onClick(event: MouseEvent): void {
    const target = (event.target as HTMLElement)?.closest('a');
    if (!target) return;
    const href = target.getAttribute('href');
    if (!href || !href.startsWith(DocPageComponent.INTERNAL_LINK_PREFIX)) return;
    event.preventDefault();
    this.router.navigateByUrl(href);
  }
}
