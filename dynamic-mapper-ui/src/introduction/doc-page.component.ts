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

import { Component, ElementRef, HostListener, OnInit, ViewChild, ViewEncapsulation, Injector, inject, ChangeDetectionStrategy, signal, viewChild } from '@angular/core';
import { afterNextRenderInZone } from '../shared/misc/after-render';
import { CoreModule } from '@c8y/ngx-components';
import { ActivatedRoute, Router } from '@angular/router';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { DocMarkdownService } from './doc-markdown.service';
import { handleDocLinkClick, scrollToDocElement } from './doc-links';

@Component({
  selector: 'd11r-doc-page',
  templateUrl: './doc-page.component.html',
  styleUrls: ['./doc-shared.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CoreModule],
  // The body is rendered markdown injected via [innerHTML], which never receives the
  // _ngcontent-* attribute Angular's default (emulated) encapsulation scopes styles with —
  // so styleUrls would silently never apply to it. doc-shared.css is already a shared,
  // unscoped stylesheet (every doc page component includes the same file), so this is safe.
  encapsulation: ViewEncapsulation.None
})
export class DocPageComponent implements OnInit {
  private readonly injector = inject(Injector);
  readonly docBodyRef = viewChild<ElementRef<HTMLElement>>('docBody');

  private readonly titleState = signal<string>('');
  get title(): string { return this.titleState(); }
  set title(value: string) { this.titleState.set(value); }
  private readonly htmlState = signal<SafeHtml>('');
  get html(): SafeHtml { return this.htmlState(); }
  set html(value: SafeHtml) { this.htmlState.set(value); }

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
      // After the render that commits the new [innerHTML], docBodyRef.nativeElement contains the
      // <pre class="mermaid"> nodes mermaid.run() needs to find.
      afterNextRenderInZone(this.injector, () => this.decorate());
    } catch (error) {
      console.error(error);
      this.title = 'Documentation unavailable';
      this.html = this.sanitizer.bypassSecurityTrustHtml(
        '<div class="admonition caution"><div class="title">Documentation unavailable</div>' +
        '<div class="content">This page could not be loaded. Please try again later.</div></div>'
      );
    }
  }

  private decorate(): void {
    const body = this.docBodyRef()?.nativeElement;
    if (!body) return; // navigated away before the macrotask ran
    this.markdownService.highlightCodeBlocks(body);
    this.markdownService.renderMermaidDiagrams(body);
  }

  scrollToElement(elementId: string): void {
    scrollToDocElement(elementId);
  }

  @HostListener('click', ['$event'])
  onClick(event: MouseEvent): void {
    handleDocLinkClick(event, this.router);
  }
}
