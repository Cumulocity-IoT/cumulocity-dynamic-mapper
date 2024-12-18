/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
import { AfterViewInit, Directive, ElementRef, Input, OnChanges, Renderer2 } from '@angular/core';

const DISABLED = 'disabled';
const APP_DISABLED = 'app-disabled';
const TAB_INDEX = 'tabindex';
const TAG_ANCHOR = 'a';

@Directive({
  selector: '[d11rAppDisable]'
})
export class DisableDirective implements OnChanges, AfterViewInit {

  @Input() appDisable = true;

  constructor(private eleRef: ElementRef, private renderer: Renderer2) { }

  ngOnChanges() {
    this.disableElement(this.eleRef.nativeElement);
  }

  ngAfterViewInit() {
    this.disableElement(this.eleRef.nativeElement);
  }

  private disableElement(element: any) {
    if (this.appDisable) {
      if (!element.hasAttribute(DISABLED)) {
        this.renderer.setAttribute(element, APP_DISABLED, '');
        this.renderer.setAttribute(element, DISABLED, 'true');

        // disabling anchor tab keyboard event
        if (element.tagName.toLowerCase() === TAG_ANCHOR) {
          this.renderer.setAttribute(element, TAB_INDEX, '-1');
        }
      }
    } else {
      if (element.hasAttribute(APP_DISABLED)) {
        if (element.getAttribute('disabled') !== '') {
          element.removeAttribute(DISABLED);
        }
        element.removeAttribute(APP_DISABLED);
        if (element.tagName.toLowerCase() === TAG_ANCHOR) {
          element.removeAttribute(TAB_INDEX);
        }
      }
    }
    if (element.children) {
      for (const ele of element.children) {
        this.disableElement(ele);
      }
    }
  }
}