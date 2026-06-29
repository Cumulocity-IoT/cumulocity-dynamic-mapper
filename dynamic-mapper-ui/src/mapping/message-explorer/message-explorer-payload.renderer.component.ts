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

import { Component } from '@angular/core';
import { CellRendererContext, CoreModule } from '@c8y/ngx-components';
import { JsonEditorComponent } from '../../shared/component/json-editor/jsoneditor.component';

@Component({
  selector: 'd11r-msg-explorer-date-renderer',
  template: `{{ context.value | c8yDate: 'MMM d, y, h:mm:ss a' }}`,
  standalone: true,
  imports: [CoreModule]
})
export class MessageExplorerDateRendererComponent {
  constructor(public readonly context: CellRendererContext) {}
}

@Component({
  selector: 'd11r-msg-explorer-payload-renderer',
  template: `
    <div>
      @if (!expanded) {
        <span class="text-monospace text-12 cursor-pointer" (click)="toggle()">
          {{ truncated }}
          @if (isLong) {
            <a class="text-primary m-l-4" translate>show more</a>
          }
        </span>
      } @else {
        @if (parsedPayload?.isJson) {
          <d11r-mapping-json-editor
            [options]="editorOptions"
            [class]="'jse-main-small'"
            [data]="parsedPayload!.parsed">
          </d11r-mapping-json-editor>
        } @else {
          <pre class="text-12 m-0">{{ parsedPayload?.raw }}</pre>
        }
        <a class="text-primary m-t-4 d-block" (click)="toggle()" translate>show less</a>
      }
      @if (context.item['binary']) {
        <span class="label label-warning m-l-4" translate>binary (base64)</span>
      }
    </div>
  `,
  standalone: true,
  imports: [CoreModule, JsonEditorComponent]
})
export class MessageExplorerPayloadRendererComponent {
  expanded = false;
  parsedPayload: { isJson: boolean; parsed: any; raw: string } | null = null;

  readonly editorOptions = {
    mode: 'tree',
    removeModes: ['text', 'table'],
    mainMenuBar: false,
    navigationBar: false,
    readOnly: true,
    statusBar: false
  };

  constructor(public readonly context: CellRendererContext) {}

  get isLong(): boolean {
    return (this.context.value as string)?.length > 120;
  }

  get truncated(): string {
    const text = this.context.value as string;
    return text && text.length > 120 ? text.substring(0, 120) + '…' : (text ?? '');
  }

  toggle(): void {
    this.expanded = !this.expanded;
    if (this.expanded && !this.parsedPayload) {
      const payload = this.context.value as string;
      try {
        this.parsedPayload = { isJson: true, parsed: JSON.parse(payload), raw: payload };
      } catch {
        this.parsedPayload = { isJson: false, parsed: null, raw: payload };
      }
    }
  }
}
