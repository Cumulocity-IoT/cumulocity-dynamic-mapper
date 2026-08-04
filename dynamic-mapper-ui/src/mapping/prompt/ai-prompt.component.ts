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
import {
  ChangeDetectorRef,
  Component,
  inject,
  Input,
  OnInit,
  AfterViewInit,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { Mapping, Substitution, MappingType, RepairStrategy, SharedService, isSubstitutionsAsCode, TransformationType } from '../../shared';
import { AlertService, BottomDrawerRef, CoreModule } from '@c8y/ngx-components';
import { AgentChatComponent } from '@c8y/ngx-components/ai/agent-chat';
import { AIMessage, ClientAgentDefinition } from '@c8y/ngx-components/ai';
import { toClientAgentDefinition } from '../core/ai-agent.service';
import { AgentObjectDefinition, AgentTextDefinition } from '../shared/ai-prompt.model';
import { ServiceConfiguration } from '../../configuration';
import { base64ToBytes } from '../shared/util';
import { EditorMode } from '../shared/stepper.model';


@Component({
  selector: 'd11r-mapping-ai-prompt',
  templateUrl: 'ai-prompt.component.html',
  styleUrls: ['./ai-prompt.component.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports:[CoreModule, AgentChatComponent],
  host: { class: 'd-contents' }
})
export class AIPromptComponent implements OnInit, AfterViewInit {

  private readonly alertService = inject(AlertService);
  private readonly sharedService = inject(SharedService);
  private readonly bottomDrawerRef = inject(BottomDrawerRef);
  private readonly cdr = inject(ChangeDetectorRef);

  @ViewChild(AgentChatComponent) agentChat?: AgentChatComponent;

  @Input() mapping: Mapping;
  @Input() aiAgent: AgentObjectDefinition | AgentTextDefinition | null;
  @Input() editorMode: EditorMode = EditorMode.CREATE;

  private _save: (value: Substitution[] | string) => void;
  private _cancel: (reason?: any) => void;
  valid: boolean = false;

  result: Promise<Substitution[] | string> = new Promise((resolve, reject) => {
    this._save = resolve;
    this._cancel = reject;
  });

  substitutions: Substitution[] = [];
  generatedCode: string = '';

  hasIssue = false;
  isLoading = false;
  newMessage = '';
  serviceConfiguration: ServiceConfiguration;
  agentType: MappingType;
  variables: Record<string, unknown> = {};
  clientAgentDefinition?: ClientAgentDefinition;
  assistantMessageDisplayConfig = {
    showDefaultToolDetails: 'all' as const,
    experimental_nonFinalStepTextDisplay: 'collapsible-thinking-block' as const
  };

  chatConfig = {
    headline: 'AI Assistant',
    welcomeText: '',
    title: 'AI Mapping Assistant',
    placeholder: 'Type your message...',
    sendButtonText: 'Send',
    cancelButtonText: 'Cancel',
    disclaimerText: 'AI-generated responses can contain errors. Verify the details before use.',
    showCumulativeUsage: true
  };

  /** Set during ngOnInit — true when the mapping already has code or substitutions */
  isReviewMode = false;
  /** Exposed for the template header */
  drawerTitle = 'AI Mapping Assistant';
  /** In UPDATE mode: true while the user is choosing review-vs-generate */
  awaitingModeChoice = false;
  /** Cached mapping object (without substitutions) ready to send to AI */
  private mappingForAI: any = null;
  /** CREATE mode auto-sends its message from ngAfterViewInit, once #agentChat exists */
  private pendingAutoSend = false;

  // Add getter to check if this is a code-based mapping
  get isCodeMapping(): boolean {
    return isSubstitutionsAsCode(this.mapping);
  }

  async ngOnInit(): Promise<void> {
    this.agentType = this.mapping.mappingType;
    this.serviceConfiguration =
      await this.sharedService.getServiceConfiguration();

    this.variables = this.aiAgent?.agent?.variables ?? {};
    this.clientAgentDefinition = this.aiAgent ? toClientAgentDefinition(this.aiAgent) : undefined;

    this.mappingForAI = this.buildMappingForAI();

    if (this.isCodeMapping) {
      this.mappingForAI.code = this.extractExistingJavaScriptCode(this.mapping);
    }

    if (this.editorMode === EditorMode.UPDATE) {
      // Let the user decide: review existing or generate from scratch
      this.awaitingModeChoice = true;
      this.drawerTitle = 'AI Mapping Assistant';
      return;
    }

    // CREATE mode — always generate. The actual send happens in ngAfterViewInit,
    // once #agentChat has been created (it doesn't exist yet during ngOnInit).
    this.prepareGenerateMessage();
    this.pendingAutoSend = true;
  }

  ngAfterViewInit(): void {
    if (this.pendingAutoSend) {
      this.pendingAutoSend = false;
      void this.sendMessage();
    }
  }

  /** Called when the user picks "Review / Refine" in the choice screen */
  async chooseReview(): Promise<void> {
    this.awaitingModeChoice = false;
    this.isReviewMode = true;
    // #agentChat doesn't exist until this flips the template's @else-if branch on —
    // force it to render now so sendMessage() below can find it via @ViewChild.
    this.cdr.detectChanges();
    this.prepareReviewMessage();
    await this.sendMessage();
  }

  /** Called when the user picks "Generate new" in the choice screen */
  async chooseGenerate(): Promise<void> {
    this.awaitingModeChoice = false;
    this.isReviewMode = false;
    this.cdr.detectChanges();
    this.prepareGenerateMessage();
    await this.sendMessage();
  }

  private prepareReviewMessage(): void {
    const direction = this.mapping.direction ?? 'INBOUND';
    const targetAPI = this.mapping.targetAPI ?? '';

    if (this.isCodeMapping) {
      this.drawerTitle = 'Review / Refine Smart Function';
      this.chatConfig = { ...this.chatConfig, title: 'Review / Refine Smart Function' };
      this.newMessage =
        `Review the existing ${direction} Smart Function` +
        (targetAPI ? ` (target: ${targetAPI})` : '') + " in the following mapping" +
        " and suggest improvements.\n\n" +
        "```json\n" + JSON.stringify(this.mappingForAI, null, 2) + "\n```\n";
    } else {
      this.drawerTitle = 'Review / Refine Substitutions';
      this.chatConfig = { ...this.chatConfig, title: 'Review / Refine Substitutions' };
      this.newMessage =
        `Review the existing substitutions for this ${direction}` +
        (targetAPI ? ` ${targetAPI}` : '') + " mapping" +
        " and suggest improvements.\n\n" +
        "```json\n" + JSON.stringify({ ...this.mappingForAI, substitutions: this.mapping.substitutions }, null, 2) + "\n```\n";
    }
  }

  private prepareGenerateMessage(): void {
    const direction = this.mapping.direction ?? 'INBOUND';
    const targetAPI = this.mapping.targetAPI ?? '';

    if (this.isCodeMapping) {
      this.drawerTitle = 'Generate Smart Function';
      this.chatConfig = { ...this.chatConfig, title: 'Generate Smart Function' };
      const isOutbound = direction === 'OUTBOUND';
      const targetTemplateIsEmpty = !this.mappingForAI.targetTemplate
        || JSON.stringify(this.mappingForAI.targetTemplate) === '{}';
      const missingContextHint = isOutbound && targetTemplateIsEmpty
        ? "The targetTemplate is empty — ask the user what device message format or protocol structure" +
          " is expected (field names, units, nesting) before generating code.\n"
        : !isOutbound && !targetAPI
          ? "The targetAPI is not set — ask the user what kind of Cumulocity object to produce" +
            " (e.g. MEASUREMENT, ALARM, EVENT, INVENTORY) before generating code.\n"
          : "";
      this.newMessage =
        `Generate a ${direction} Smart Function for the following mapping` +
        (targetAPI ? ` (target: ${targetAPI})` : '') + ".\n" +
        missingContextHint +
        "\n```json\n" + JSON.stringify(this.mappingForAI, null, 2) + "\n```\n";
    } else {
      this.drawerTitle = 'Generate Substitutions';
      this.chatConfig = { ...this.chatConfig, title: 'Generate Substitutions' };
      this.newMessage =
        `Generate JSONata substitutions for this ${direction}` +
        (targetAPI ? ` ${targetAPI}` : '') + " mapping.\n\n" +
        "```json\n" + JSON.stringify(this.mappingForAI, null, 2) + "\n```\n";
    }
  }

  save() {
    if (this.isCodeMapping) {
      this._save(this.generatedCode);
    } else {
      this._save(this.substitutions);
    }
    this.bottomDrawerRef.close();
  }

  cancel() {
    this._cancel("User canceled");
    this.bottomDrawerRef.close();
  }

  private buildMappingForAI(): any {
    const m: any = { ...this.mapping };
    delete m.substitutions;
    m.sourceTemplate = JSON.parse(m.sourceTemplate as any);
    m.targetTemplate = JSON.parse(m.targetTemplate as any);
    // Tell the AI whether ESM exports are required in the generated code
    m.supportESM = this.serviceConfiguration?.supportESM ?? false;
    return m;
  }

  private extractExistingJavaScriptCode(mapping: Mapping): string {
    if (!mapping.code) {
      return '';
    }
    const enc = new TextDecoder("utf-8");
    const mappingCodeTemplateDecoded = enc.decode(base64ToBytes(mapping.code));
    return mappingCodeTemplateDecoded;
  }

  async sendMessage(): Promise<void> {
    if (!this.aiAgent || !this.newMessage) {
      return;
    }
    if (!this.agentChat) {
      console.error('AIPromptComponent.sendMessage: #agentChat is not available yet');
      return;
    }
    await this.agentChat.sendMessage({ role: 'user', content: this.newMessage });
    this.newMessage = '';
  }

  /** Called via (onMessageFinish) once the agent's response has fully streamed in. */
  onMessageFinish(message: AIMessage): void {
    if (message.role !== 'assistant') {
      return;
    }

    const content = message.content
      .map(part => {
        if (part.type === 'text') return part.text;
        if (part.type === 'object') return part.jsonContent;
        return '';
      })
      .filter(text => text.length > 0)
      .join('\n\n');

    if (this.isCodeMapping) {
      this.checkIfResponseContainsJavaScript(content);
    } else {
      this.checkIfResponseContainsSubstitutions(content);
    }
  }

  /**
   * Extracts JavaScript from the latest assistant response, if present, and marks it valid for saving.
   * A response with no code block (e.g. a clarifying question or acknowledgement) is common in a
   * multi-turn conversation and must NOT revoke a previously extracted, valid `generatedCode` —
   * so this only ever flips `valid` from false to true, never the other way around.
   */
  checkIfResponseContainsJavaScript(content: any): void {
    try {
      // Look for JavaScript code blocks
      const jsBlockRegex = /```javascript\s*([\s\S]*?)\s*```/;
      const match = content.match(jsBlockRegex);

      let jsContent: string | undefined;
      if (match && match[1]) {
        jsContent = match[1].trim();
      } else {
        // Try alternative patterns for code blocks
        const genericCodeRegex = /```(?:js|javascript)?\s*([\s\S]*?)\s*```/;
        const genericMatch = content.match(genericCodeRegex);
        if (genericMatch && genericMatch[1]) {
          jsContent = genericMatch[1].trim();
        }
      }

      if (jsContent?.includes('function') && jsContent.includes('function onMessage')) {
        this.generatedCode = this.applyESMExport(jsContent);
        this.valid = true;
      }
      // else: no code block, or one without a recognizable onMessage function — keep the
      // previous generatedCode/valid state untouched rather than disabling Save.
    } catch (error) {
      console.error('Error parsing JavaScript from response:', error);
      this.alertService.danger('Failed to parse JavaScript code from AI response');
    }
  }

  private applyESMExport(code: string): string {
    if (!this.serviceConfiguration?.supportESM) return code;
    if (this.mapping.transformationType !== TransformationType.SMART_FUNCTION) return code;

    const exportStatement = `export { onMessage };`;
    if (code.includes(exportStatement)) return code;

    return code.trimEnd() +
      '\n\n// ── ESM export (added automatically because Support ESM is enabled) ──────────\n' +
      exportStatement + '\n';
  }

  /**
   * Extracts substitutions from the latest assistant response, if present, and marks them valid for saving.
   * A response with no JSON block (e.g. a clarifying question or acknowledgement) is common in a
   * multi-turn conversation and must NOT revoke a previously extracted, valid `substitutions` array —
   * so this only ever flips `valid` from false to true, never the other way around.
   */
  checkIfResponseContainsSubstitutions(content: any) {
    try {
      // Look for the pattern ```json followed by content and ending with ```
      const jsonBlockRegex = /```json\s*([\s\S]*?)\s*```/;
      const match = content.match(jsonBlockRegex);

      if (!match || !match[1]) {
        // No JSON block in this response — keep the previous substitutions/valid state untouched.
        return;
      }

      const parsedSubstitutions = JSON.parse(match[1].trim());

      if (!Array.isArray(parsedSubstitutions)) {
        return;
      }

      const isValidSubstitutions = parsedSubstitutions.every(sub =>
        sub.hasOwnProperty('pathSource') &&
        sub.hasOwnProperty('pathTarget') &&
        sub.hasOwnProperty('expandArray')
      );

      if (isValidSubstitutions) {
        // The LLM's JSON block may omit repairStrategy (or emit it as null) — default it here so
        // every downstream consumer (edit modal, persisted mapping) always sees a real value
        // instead of undefined/null.
        this.substitutions = parsedSubstitutions.map(sub => ({
          ...sub,
          repairStrategy: sub.repairStrategy ?? RepairStrategy.DEFAULT
        }));
        this.valid = true;
      }
    } catch (error) {
      console.error('Error parsing substitutions from response:', error);
      this.alertService.danger('Failed to parse substitutions from AI response');
    }
  }

}
