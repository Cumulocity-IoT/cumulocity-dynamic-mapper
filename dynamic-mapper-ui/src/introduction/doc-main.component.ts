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

import { Component, OnDestroy, OnInit, AfterViewChecked, ElementRef, ViewChild } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
import { ActivatedRoute } from '@angular/router';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { DocOverviewComponent } from './doc-overview.component';
import { DocJsonataComponent } from './doc-jsonata.component';
import { DocJavaScriptComponent } from './doc-javascript.component';
import { DocSmartFunctionComponent } from './doc-smartfunction.component';
import { DocJavaExtensionComponent } from './doc-javaextension.component';

import hljs from 'highlight.js/lib/core';
import javascript from 'highlight.js/lib/languages/javascript';
import java from 'highlight.js/lib/languages/java';
import yaml from 'highlight.js/lib/languages/yaml';

hljs.registerLanguage('javascript', javascript);
hljs.registerLanguage('java', java);
hljs.registerLanguage('yaml', yaml);

@Component({
  selector: 'd11r-landing',
  templateUrl: './doc-main.component.html',
  styleUrls: ['./doc-shared.css'],
  standalone: true,
  imports: [
    CoreModule,
    CommonModule,
    DocOverviewComponent,
    DocJsonataComponent,
    DocJavaScriptComponent,
    DocSmartFunctionComponent,
    DocJavaExtensionComponent
  ]
})
export class DocMainComponent implements OnInit, OnDestroy, AfterViewChecked {
  private highlightApplied = false;

  @ViewChild('docContent', { static: false }) docContentRef: ElementRef<HTMLElement>;

  searchQuery: string = '';
  searchMatchCount: number = 0;
  searchCurrentIndex: number = -1;
  private searchMatches: HTMLElement[] = [];

  currentPage: string = 'main';
  private fragmentSubscription: Subscription;

  constructor(private route: ActivatedRoute) {}

  ngOnInit(): void {
    const path = this.route.snapshot.routeConfig?.path || '';

    if (path.includes('jsonata')) {
      this.currentPage = 'jsonata';
    } else if (path.includes('javascript')) {
      this.currentPage = 'javascript';
    } else if (path.includes('smartfunction')) {
      this.currentPage = 'smartfunction';
    } else if (path.includes('javaextension')) {
      this.currentPage = 'javaextension';
    } else {
      this.currentPage = 'main';
    }

    // For section paths that live inside the 'main' overview page (e.g. /landing/sparkplugb),
    // extract the last path segment and use it as a scroll target after the view renders.
    const sectionPaths = [
      'overview', 'getting-started', 'managing-connectors', 'define-mapping',
      'sparkplugb', 'define-subscription-for-outbound', 'transformation-types',
      'flow-state', 'code-templates', 'metadata', 'unknown-payload',
      'reliability-settings', 'access-control', 'monitoring', 'troubleshooting'
    ];
    const lastSegment = path.split('/').pop() || '';
    if (sectionPaths.includes(lastSegment)) {
      setTimeout(() => { this.scrollToElement(lastSegment); }, 200);
    }

    this.clearSearch();
    this.highlightApplied = false;

    this.fragmentSubscription = this.route.fragment.subscribe(fragment => {
      if (fragment) {
        setTimeout(() => { this.scrollToElement(fragment); }, 150);
      }
    });
  }

  onSearch(): void {
    this.clearHighlights();
    this.searchMatches = [];
    this.searchCurrentIndex = -1;
    const query = this.searchQuery.trim();
    if (query.length < 2) { this.searchMatchCount = 0; return; }
    const root = this.docContentRef?.nativeElement;
    if (!root) return;
    this.searchMatches = this.applyHighlights(root, query);
    this.searchMatchCount = this.searchMatches.length;
    if (this.searchMatches.length > 0) { this.searchCurrentIndex = 0; this.scrollToMatch(0); }
  }

  clearSearch(): void {
    this.searchQuery = '';
    this.clearHighlights();
    this.searchMatches = [];
    this.searchMatchCount = 0;
    this.searchCurrentIndex = -1;
  }

  nextMatch(): void {
    if (this.searchMatches.length === 0) return;
    this.searchCurrentIndex = (this.searchCurrentIndex + 1) % this.searchMatches.length;
    this.scrollToMatch(this.searchCurrentIndex);
  }

  prevMatch(): void {
    if (this.searchMatches.length === 0) return;
    this.searchCurrentIndex = (this.searchCurrentIndex - 1 + this.searchMatches.length) % this.searchMatches.length;
    this.scrollToMatch(this.searchCurrentIndex);
  }

  private scrollToMatch(index: number): void {
    this.searchMatches.forEach(m => m.classList.remove('search-highlight--current'));
    const el = this.searchMatches[index];
    if (!el) return;
    el.classList.add('search-highlight--current');
    const rect = el.getBoundingClientRect();
    const absoluteTop = window.scrollY + rect.top;
    window.scrollTo({ top: absoluteTop - 130, behavior: 'smooth' });
  }

  private clearHighlights(): void {
    const root = this.docContentRef?.nativeElement;
    if (!root) return;

    root.querySelectorAll('mark.search-highlight').forEach(mark => {
      const parent = mark.parentNode;
      if (parent) {
        parent.replaceChild(root.ownerDocument.createTextNode(mark.textContent || ''), mark);
        parent.normalize();
      }
    });
  }

  private applyHighlights(root: HTMLElement, query: string): HTMLElement[] {
    const matches: HTMLElement[] = [];
    const regex = new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi');
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        const parent = (node as Text).parentElement;
        if (!parent) return NodeFilter.FILTER_SKIP;
        const tag = parent.tagName.toLowerCase();
        if (tag === 'script' || tag === 'style') return NodeFilter.FILTER_SKIP;
        if (parent.closest('mark')) return NodeFilter.FILTER_SKIP;
        if (parent.closest('pre')) return NodeFilter.FILTER_SKIP; // skip hljs-highlighted code blocks
        return (node.textContent?.trim()) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
      }
    });
    const textNodes: Text[] = [];
    while (walker.nextNode()) textNodes.push(walker.currentNode as Text);
    textNodes.forEach(node => {
      const text = node.textContent || '';
      regex.lastIndex = 0;
      if (!regex.test(text)) return;
      regex.lastIndex = 0;
      const fragment = document.createDocumentFragment();
      let lastIndex = 0;
      let match: RegExpExecArray | null;
      while ((match = regex.exec(text)) !== null) {
        if (match.index > lastIndex) fragment.appendChild(document.createTextNode(text.slice(lastIndex, match.index)));
        const mark = document.createElement('mark');
        mark.className = 'search-highlight';
        mark.textContent = match[0];
        fragment.appendChild(mark);
        matches.push(mark);
        lastIndex = regex.lastIndex;
      }
      if (lastIndex < text.length) fragment.appendChild(document.createTextNode(text.slice(lastIndex)));
      node.parentNode?.replaceChild(fragment, node);
    });
    return matches;
  }

  scrollToElement(elementId: string): void {
    const element = document.getElementById(elementId);
    if (element) {
      const elementPosition = element.getBoundingClientRect().top + window.scrollY;
      window.scrollTo({ top: elementPosition - 120, behavior: 'smooth' });
    }
  }

  ngAfterViewChecked(): void {
    if (!this.highlightApplied) {
      setTimeout(() => {
        document.querySelectorAll('pre code').forEach((block) => {
          hljs.highlightElement(block as HTMLElement);
        });
        this.addCopyButtons();
        this.highlightApplied = true;
      }, 100);
    }
  }

  private addCopyButtons(): void {
    document.querySelectorAll('pre code').forEach((codeElement: Element) => {
      const pre = codeElement.parentElement;
      if (!pre || pre.querySelector('.btn-copy-code')) return;
      (pre as HTMLElement).style.position = 'relative';
      const toolbar = document.createElement('div');
      toolbar.className = 'code-toolbar';
      toolbar.style.cssText = 'display:flex;flex-direction:row;justify-content:flex-end;align-items:center;background-color:#000000';
      const button = document.createElement('button');
      button.className = 'btn-copy-code';
      button.setAttribute('type', 'button');
      button.setAttribute('aria-label', 'Copy code to clipboard');
      button.style.cssText = 'margin:2px 4px 4px auto;height:18px;background-color:#000000;font-size:12px';
      const icon = document.createElement('i');
      icon.className = 'dlt-c8y-icon-clipboard';
      icon.style.marginRight = '4px';
      button.appendChild(icon);
      button.appendChild(document.createTextNode('Copy to clipboard'));
      button.addEventListener('click', async () => {
        const code = codeElement.textContent || '';
        try {
          await navigator.clipboard.writeText(code);
          icon.className = 'dlt-c8y-icon-ok';
          button.childNodes[1].textContent = 'Copied!';
          button.classList.add('copied');
          setTimeout(() => { icon.className = 'dlt-c8y-icon-clipboard'; button.childNodes[1].textContent = 'Copy to clipboard'; button.classList.remove('copied'); }, 2000);
        } catch {
          icon.className = 'dlt-c8y-icon-remove';
          button.childNodes[1].textContent = 'Failed';
          setTimeout(() => { icon.className = 'dlt-c8y-icon-clipboard'; button.childNodes[1].textContent = 'Copy to clipboard'; }, 2000);
        }
      });
      toolbar.appendChild(button);
      pre.insertBefore(toolbar, pre.firstChild);
    });
  }

  ngOnDestroy(): void {
    if (this.fragmentSubscription) { this.fragmentSubscription.unsubscribe(); }
  }
}
