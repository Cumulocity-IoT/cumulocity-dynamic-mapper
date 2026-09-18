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
import { CommonModule } from '@angular/common';
import { CoreModule, BottomDrawerService } from '@c8y/ngx-components';
import { Router } from '@angular/router';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { DocMarkdownService } from './doc-markdown.service';
import { SharedService } from '../shared/service/shared.service';
import { CodeTemplate, CodeTemplateMap } from '../shared/configuration/configuration.model';
import { CodeEditorDrawerComponent } from '../shared/component/code-explorer/code-editor-drawer.component';

/**
 * The Code Templates page: markdown prose plus the live gallery of the templates that actually
 * exist in this tenant. The gallery has to be a real Angular template (not markdown) because it
 * iterates live data and opens the code drawer — content injected via [innerHTML] is never
 * evaluated by Angular, so bindings there would render as literal text.
 *
 * It previously lived as an appendix at the bottom of the single combined overview page; it moved
 * here with the rest of the Code Templates content when the documentation was split per topic.
 */
@Component({
  selector: 'd11r-doc-code-templates',
  templateUrl: './doc-code-templates.component.html',
  styleUrls: ['./doc-shared.css'],
  standalone: true,
  imports: [CoreModule, CommonModule],
  // See DocPageComponent: rendered markdown injected via [innerHTML] never receives the
  // _ngcontent-* attribute emulated encapsulation scopes styles with.
  encapsulation: ViewEncapsulation.None
})
export class DocCodeTemplatesComponent implements OnInit {
  @ViewChild('docBody', { static: false }) docBodyRef: ElementRef<HTMLElement>;

  title = 'Code templates';
  html: SafeHtml = '';
  codeTemplates: CodeTemplate[] = [];

  private static readonly INTERNAL_LINK_PREFIX = '/c8y-pkg-dynamic-mapper/';

  constructor(
    private router: Router,
    private markdownService: DocMarkdownService,
    private sanitizer: DomSanitizer,
    private sharedService: SharedService,
    private bottomDrawerService: BottomDrawerService
  ) {}

  async ngOnInit(): Promise<void> {
    const rendered = await this.markdownService.loadAndRender('code-templates');
    this.title = rendered.title;
    this.html = this.sanitizer.bypassSecurityTrustHtml(rendered.html);
    setTimeout(() => this.markdownService.renderMermaidDiagrams(this.docBodyRef.nativeElement));

    const codeTemplatesMap: CodeTemplateMap = await this.sharedService.getCodeTemplates();
    this.codeTemplates = Object.entries(codeTemplatesMap)
      .map(([, template]) => template)
      .sort((a, b) => {
        const typeOrder = {
          'INBOUND_SMART_FUNCTION': 1,
          'OUTBOUND_SMART_FUNCTION': 2,
          'SHARED': 3,
          'SYSTEM': 4
        };
        const typeComparison = (typeOrder[a.templateType] || 999) - (typeOrder[b.templateType] || 999);
        if (typeComparison !== 0) return typeComparison;
        return a.name.localeCompare(b.name);
      });
  }

  openCodeExplorer(template: CodeTemplate): void {
    this.bottomDrawerService.openDrawer(CodeEditorDrawerComponent, {
      initialState: { encodedCode: template.code, sourceSystem: 'Template', action: 'view' }
    });
  }

  getTransformationTypeName(templateType: string): string {
    switch (templateType) {
      case 'INBOUND_SMART_FUNCTION':
      case 'OUTBOUND_SMART_FUNCTION':
        return 'Smart Functions';
      case 'SHARED':
        return 'Shared Code';
      case 'SYSTEM':
        return 'System Code';
      case 'INBOUND':
        return 'Inbound (deprecated)';
      case 'OUTBOUND':
        return 'Outbound (deprecated)';
      default:
        return templateType;
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
    if (!href) return;
    if (href.startsWith('#')) {
      event.preventDefault();
      this.scrollToElement(href.slice(1));
      return;
    }
    if (!href.startsWith(DocCodeTemplatesComponent.INTERNAL_LINK_PREFIX)) return;
    event.preventDefault();
    this.router.navigateByUrl(href);
  }
}
