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
import { JsonEditorComponent } from '../../shared/component/json-editor/json-editor.component';

@Component({
  selector: 'd11r-msg-explorer-date-renderer',
  template: `<span class="text-nowrap" [title]="context.value | c8yDate: 'medium'">{{ context.value | c8yDate: 'MMM d, HH:mm:ss.SSS' }}</span>`,
  standalone: true,
  imports: [CoreModule]
})
export class MessageExplorerDateRendererComponent {
  constructor(public readonly context: CellRendererContext) {}
}

@Component({
  selector: 'd11r-msg-explorer-truncate-renderer',
  template: `<span class="d-block text-truncate" [title]="context.value ?? ''">{{ context.value }}</span>`,
  standalone: true,
  imports: [CoreModule]
})
export class MessageExplorerTruncateRendererComponent {
  constructor(public readonly context: CellRendererContext) {}
}

@Component({
  selector: 'd11r-msg-explorer-payload-renderer',
  styles: [`
    .binary-summary { display: flex; align-items: center; gap: 8px; min-width: 0; cursor: pointer; }
    .binary-summary .label { flex: 0 0 auto; max-width: none; overflow: visible; text-overflow: clip; }
    .binary-summary .preview { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
    .binary-view { max-height: 300px; overflow: auto; margin: 4px 0 0; }
    .binary-view.wrap { white-space: pre-wrap; word-break: break-all; }
  `],
  template: `
    @if (isBinary) {
      <div>
        <div class="binary-summary" (click)="toggle()">
          <span class="label label-warning text-nowrap">{{ binaryFormat ? (binaryFormat) : ("binary" | translate) }}</span>
          <span class="text-muted text-12 text-nowrap">{{ byteLength }} B</span>
          @if (!expanded) {
            <span class="preview text-monospace text-12">{{ textPreview }}</span>
          }
        </div>
        @if (expanded) {
          <div class="btn-group btn-group-sm m-t-4" role="group">
            @for (v of views; track v.id) {
              <button type="button" class="btn btn-default" [class.active]="view === v.id"
                (click)="view = v.id">{{ v.label | translate }}</button>
            }
          </div>
          <pre class="binary-view text-12" [class.wrap]="view !== 'hex'">{{ viewContent }}</pre>
          <a class="text-primary m-t-4 d-block" (click)="toggle()" translate>show less</a>
        }
      </div>
    } @else {
      <div>
        @if (!expanded) {
          @if (textTypeLabel) {
            <span class="label label-default m-r-8 text-nowrap">{{ textTypeLabel }}</span>
          }
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
      </div>
    }
  `,
  standalone: true,
  imports: [CoreModule, JsonEditorComponent]
})
export class MessageExplorerPayloadRendererComponent {
  expanded = false;
  view: 'text' | 'hex' | 'base64' = 'text';
  readonly views = [
    { id: 'text' as const, label: 'Text' },
    { id: 'hex' as const, label: 'Hex' },
    { id: 'base64' as const, label: 'Base64' }
  ];
  parsedPayload: { isJson: boolean; parsed: any; raw: string } | null = null;

  private bytesCache: Uint8Array | null | undefined;
  private textCache: string | undefined;
  private hexCache: string | undefined;

  readonly editorOptions = {
    mode: 'tree',
    removeModes: ['text', 'table'],
    mainMenuBar: false,
    navigationBar: false,
    readOnly: true,
    statusBar: false
  };

  constructor(public readonly context: CellRendererContext) {}

  get isBinary(): boolean {
    return !!this.context.item['binary'] && this.bytes !== null;
  }

  /** Well-known format of the binary payload (e.g. GZIP) detected by the backend, if any. */
  get binaryFormat(): string | null {
    return (this.context.item['binaryFormat'] as string) || null;
  }

  /** Type badge for text payloads, from the backend's detection (XML, TEXT, JSON); none when empty or unknown. */
  get textTypeLabel(): string | null {
    const type = this.context.item['payloadType'] as string | undefined;
    return type && type !== 'EMPTY' && type !== 'BINARY' ? type : null;
  }

  get isLong(): boolean {
    return (this.context.value as string)?.length > 120;
  }

  get truncated(): string {
    const text = this.context.value as string;
    return text && text.length > 120 ? text.substring(0, 120) + '…' : (text ?? '');
  }

  /** Decoded bytes of the base64 payload; null if the text is not valid base64. */
  private get bytes(): Uint8Array | null {
    if (this.bytesCache === undefined) {
      try {
        const bin = atob((this.context.value as string) ?? '');
        this.bytesCache = Uint8Array.from(bin, (c) => c.charCodeAt(0));
      } catch {
        this.bytesCache = null;
      }
    }
    return this.bytesCache;
  }

  get byteLength(): number {
    return this.bytes?.length ?? 0;
  }

  /** UTF-8 text of the bytes with control characters shown as a middle dot. */
  private get fullText(): string {
    if (this.textCache === undefined) {
      const decoded = new TextDecoder('utf-8').decode(this.bytes ?? new Uint8Array());
      this.textCache = decoded.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f�]/g, '·');
    }
    return this.textCache;
  }

  get textPreview(): string {
    const t = this.fullText;
    return t.length > 120 ? t.substring(0, 120) + '…' : t;
  }

  get viewContent(): string {
    switch (this.view) {
      case 'hex':
        return (this.hexCache ??= this.hexDump(this.bytes ?? new Uint8Array()));
      case 'base64':
        return (this.context.value as string) ?? '';
      default:
        return this.fullText;
    }
  }

  /** Offset, 16 hex bytes and a printable-ASCII column per line. */
  private hexDump(bytes: Uint8Array): string {
    const lines: string[] = [];
    for (let off = 0; off < bytes.length; off += 16) {
      const chunk = Array.from(bytes.subarray(off, off + 16));
      const hex = chunk.map((b) => b.toString(16).padStart(2, '0')).join(' ').padEnd(47, ' ');
      const ascii = chunk.map((b) => (b >= 0x20 && b < 0x7f ? String.fromCharCode(b) : '.')).join('');
      lines.push(`${off.toString(16).padStart(8, '0')}  ${hex}  ${ascii}`);
    }
    return lines.join('\n');
  }

  toggle(): void {
    this.expanded = !this.expanded;
    if (this.expanded && !this.parsedPayload && !this.isBinary) {
      // The backend already told us whether the text is JSON; only try to parse it when it is.
      const hint = this.context.item['payloadType'] as string | undefined;
      const payload = this.context.value as string;
      if (hint === 'XML' || hint === 'TEXT') {
        this.parsedPayload = { isJson: false, parsed: null, raw: payload };
        return;
      }
      try {
        this.parsedPayload = { isJson: true, parsed: JSON.parse(payload), raw: payload };
      } catch {
        this.parsedPayload = { isJson: false, parsed: null, raw: payload };
      }
    }
  }
}
