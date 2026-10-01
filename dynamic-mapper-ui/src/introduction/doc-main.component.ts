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

import { Component, OnDestroy, OnInit, ElementRef, ViewChild } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
import { ActivatedRoute, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { CommonModule } from '@angular/common';
import { Subscription, filter } from 'rxjs';
import { scrollToDocElement } from './doc-links';


@Component({
  selector: 'd11r-landing',
  templateUrl: './doc-main.component.html',
  styleUrls: ['./doc-shared.css'],
  standalone: true,
  imports: [
    CoreModule,
    CommonModule,
    RouterOutlet,
  ]
})
export class DocMainComponent implements OnInit, OnDestroy {
  @ViewChild('docContent', { static: false }) docContentRef: ElementRef<HTMLElement>;

  searchQuery: string = '';
  searchMatchCount: number = 0;
  searchCurrentIndex: number = -1;
  private searchMatches: HTMLElement[] = [];

  private fragmentSubscription: Subscription;
  private routerSubscription: Subscription;

  constructor(private route: ActivatedRoute, private router: Router) {}

  ngOnInit(): void {
    this.clearSearch();

    this.fragmentSubscription = this.route.fragment.subscribe(fragment => {
      if (fragment) {
        setTimeout(() => { this.scrollToElement(fragment); }, 150);
      }
    });

    // Reset search whenever a child route changes
    this.routerSubscription = this.router.events
      .pipe(filter(e => e instanceof NavigationEnd))
      .subscribe(() => {
        this.clearSearch();
        // Scroll to top on every navigation so the incoming page always starts at the
        // top of the viewport. This must be 'instant' (not 'smooth') so it completes
        // before DocOverviewComponent.ngOnInit fires its own 200ms scroll-to-section.
        window.scrollTo({ top: 0, behavior: 'instant' });
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
    scrollToDocElement(elementId);
  }

  ngOnDestroy(): void {
    if (this.fragmentSubscription) { this.fragmentSubscription.unsubscribe(); }
    if (this.routerSubscription) { this.routerSubscription.unsubscribe(); }
  }
}
