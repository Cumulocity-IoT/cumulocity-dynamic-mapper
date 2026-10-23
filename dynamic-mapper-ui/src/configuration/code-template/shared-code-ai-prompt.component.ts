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
import { ChangeDetectorRef, Component, inject, Input, OnInit, ViewEncapsulation } from '@angular/core';
import { BottomDrawerRef, CoreModule } from '@c8y/ngx-components';
import { AIMessage, AgentChatConfig, ClientAgentDefinition, Suggestion } from '@c8y/ngx-components/ai';
import { AgentChatComponent } from '@c8y/ngx-components/ai/agent-chat';
import { gettext } from '@c8y/ngx-components/gettext';
import { TranslateService } from '@ngx-translate/core';
import { AiAgentService, toClientAgentDefinition } from '../../mapping/core/ai-agent.service';
import { AgentObjectDefinition, AgentTextDefinition } from '../../shared/mapping/ai-prompt.model';

/**
 * Chat drawer for creating, modifying and reviewing the SHARED code template with AI.
 *
 * The result is only handed back to the caller (which puts it into the editor); nothing is saved
 * until the user presses Save on the code template page.
 */
@Component({
  selector: 'd11r-shared-code-ai-prompt',
  templateUrl: 'shared-code-ai-prompt.component.html',
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, AgentChatComponent],
  host: { class: 'd-contents' }
})
export class SharedCodeAiPromptComponent implements OnInit {
  private readonly bottomDrawerRef = inject(BottomDrawerRef);
  private readonly aiAgentService = inject(AiAgentService);
  private readonly translateService = inject(TranslateService);
  private readonly cdr = inject(ChangeDetectorRef);

  /** Current content of the shared code editor (decoded). */
  @Input() code = '';
  /** Decoded SYSTEM template, so the agent knows which internal classes/globals already exist. */
  @Input() systemCode = '';
  @Input() aiAgent: AgentObjectDefinition | AgentTextDefinition | null = null;

  private _save!: (value: string) => void;
  private _cancel!: (reason?: any) => void;
  result: Promise<string> = new Promise((resolve, reject) => {
    this._save = resolve;
    this._cancel = reject;
  });

  generatedCode = '';
  valid = false;
  aiChatEnabled = false;
  aiCapabilitiesDisabled = false;
  aiAvailabilityCheckFailed = false;
  telemetryEnabled = true;
  variables: Record<string, unknown> = {};
  clientAgentDefinition?: ClientAgentDefinition;
  suggestions: Suggestion[] = [];

  chatConfig: Partial<AgentChatConfig> = {
    headline: this.translateService.instant(gettext('AI Assistant')),
    title: this.translateService.instant(gettext('AI Shared Code Assistant')),
    welcomeText: this.translateService.instant(
      gettext(
        'I can create, modify or review the shared code that is available to all Smart Functions. ' +
          'Pick an option below, or describe what you need. Nothing is saved until you press Save on the page.'
      )
    ),
    placeholder: this.translateService.instant(gettext('Type your message...')),
    sendButtonText: this.translateService.instant(gettext('Send')),
    cancelButtonText: this.translateService.instant(gettext('Cancel')),
    disclaimerText: this.translateService.instant(
      gettext('AI-generated responses can contain errors. Verify the details before use.')
    ),
    showCumulativeUsage: true
  };

  async ngOnInit(): Promise<void> {
    try {
      const settings = await this.aiAgentService.getAISettings(true);
      if (settings.killSwitch) {
        this.aiCapabilitiesDisabled = true;
        this.cdr.detectChanges();
        return;
      }
      this.telemetryEnabled = !settings.telemetryOptOut;
    } catch (error) {
      console.error('Failed to check AI settings before opening the AI assistant:', error);
      this.aiAvailabilityCheckFailed = true;
      this.cdr.detectChanges();
      return;
    }

    this.variables = this.aiAgent?.agent?.variables ?? {};
    this.clientAgentDefinition = this.aiAgent ? toClientAgentDefinition(this.aiAgent) : undefined;
    this.suggestions = [
      {
        label: 'Review existing shared code',
        prompt: 'Review the existing shared code and suggest improvements.\n',
        icon: 'search'
      },
      {
        label: 'Create new shared code',
        prompt:
          'I want to create new shared code. Ask me what helper functions or constants I need, ' +
          'then wait for my answer before generating anything.\n',
        icon: 'plus-circle-o'
      }
    ];
    this.aiChatEnabled = true;
    this.cdr.detectChanges();
  }

  /**
   * Re-sent with every user message instead of being embedded in the visible chat, so the agent
   * always sees the editor's current content, including edits made between messages.
   */
  groundingContextProvider = (): string =>
    '[SHARED_CODE_CONTEXT] This conversation is NOT about a mapping or an onMessage function. ' +
    'The user is editing the SHARED code template: plain JavaScript that is evaluated once per ' +
    'GraalVM context, before every Smart Function, so its top-level declarations (functions, ' +
    'classes, constants) become globals that all Smart Functions can call without any import. ' +
    'Do not generate an onMessage function and do not call the submit-smart-function tool. ' +
    'Reply with the COMPLETE new content of the shared code in one ```javascript block, keeping the ' +
    'leading JSDoc header (@name, @description, @templateType, @defaultTemplate) unchanged. ' +
    'Do not redefine anything already declared in the system code.\n\n' +
    'Current shared code:\n```javascript\n' + this.code + '\n```\n\n' +
    (this.systemCode
      ? 'System code (read-only, already available as globals):\n```javascript\n' + this.systemCode + '\n```\n'
      : '');

  /** Called via (onMessageFinish): the last ```javascript block of the reply is the proposed code. */
  onMessageFinish(message: AIMessage): void {
    if (message.role !== 'assistant') return;
    const content = message.content
      .map(part => (part.type === 'text' ? part.text : ''))
      .filter(text => text.length > 0)
      .join('\n\n');
    const blocks = [...content.matchAll(/```(?:javascript|js)\s*([\s\S]*?)\s*```/g)].map(m => m[1].trim());
    const candidate = blocks.reverse().find(b => b.length > 0);
    // A reply without a code block (e.g. a clarifying question) must not revoke an earlier result.
    if (candidate) {
      this.generatedCode = candidate;
      this.valid = true;
    }
    this.cdr.detectChanges();
  }

  save(): void {
    this._save(this.generatedCode);
    this.bottomDrawerRef.close();
  }

  cancel(): void {
    this._cancel('User canceled');
    this.bottomDrawerRef.close();
  }
}
