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
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { Mapping, Substitution, MappingType, RepairStrategy, SAMPLE_TEMPLATES_C8Y, SharedService, isSubstitutionsAsCode, TransformationType, getGenericDeviceIdentifier } from '../../shared';
import { AlertService, BottomDrawerRef, CoreModule } from '@c8y/ngx-components';
import { AgentChatComponent } from '@c8y/ngx-components/ai/agent-chat';
import { AIMessage, ClientAgentDefinition, Suggestion } from '@c8y/ngx-components/ai';
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
export class AIPromptComponent implements OnInit {

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

  /** Exposed for the template header */
  drawerTitle = 'AI Mapping Assistant';
  /**
   * UPDATE mode: clickable suggestion chips rendered inside the chat (via #agentChat's
   * own `suggestions` input) so the user can pick review-vs-generate without a separate
   * blocking screen. Left undefined in CREATE mode, where there's nothing to review yet
   * and generation is triggered immediately instead.
   */
  suggestions?: Suggestion[];
  /** Cached mapping object (without substitutions) ready to send to AI */
  private mappingForAI: any = null;

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

    // ngOnInit is async, and everything above runs after its first await — by then
    // Angular has already run its *initial* change detection (and ngAfterViewInit), using
    // whatever state existed at that pause point, so relying on either to pick up state
    // set here doesn't work. #agentChat's host `@if` depends on clientAgentDefinition,
    // only just assigned above, so force a render now before touching it further.
    this.cdr.detectChanges();

    if (this.editorMode === EditorMode.UPDATE) {
      // Existing code/substitutions to review — offer both as suggestion chips inside
      // the chat itself and let the user pick, instead of auto-sending anything.
      const label = this.isCodeMapping ? 'Smart Function' : 'substitutions';
      this.suggestions = [
        { label: `Review / Refine existing ${label}`, prompt: this.buildReviewMessage(), icon: 'search' },
        { label: `Generate new ${label} from scratch`, prompt: this.buildGenerateMessage(), icon: 'plus-circle-o' }
      ];
      this.chatConfig = {
        ...this.chatConfig,
        welcomeText: this.isCodeMapping
          ? "This mapping already has a Smart Function. I can review the existing code and suggest " +
            "improvements, or generate a new one from scratch based on the mapping's source and target " +
            "templates. Pick an option below, or describe what you'd like changed."
          : "This mapping already has substitutions defined. I can review them and suggest improvements, " +
            "or generate a new set from scratch based on the mapping's source and target templates. " +
            "Pick an option below, or describe what you'd like changed."
      };
      this.cdr.detectChanges();
      return;
    }

    // CREATE mode, Smart Function: ask what to generate first, via the same suggestion-chip
    // mechanism as UPDATE mode's Review/Generate choice — targetTemplate is always empty by
    // design for code mappings (see buildGenerateMessage()), so a sample payload is the only
    // way the user can hand the AI concrete context beyond the source template/targetAPI.
    if (this.isCodeMapping) {
      this.drawerTitle = 'Generate Smart Function';
      this.suggestions = [
        { label: 'Let AI propose a target structure', prompt: this.buildGenerateMessage(), icon: 'magic' },
        { label: 'I want to provide a sample target payload', prompt: this.buildProvideSampleMessage(), icon: 'code' }
      ];
      this.chatConfig = {
        ...this.chatConfig,
        title: this.drawerTitle,
        welcomeText: "I can generate a Smart Function for this mapping based on its source template. " +
          "There's no fixed target template — pick an option below, or describe the target payload" +
          " you'd like it to produce."
      };
      this.cdr.detectChanges();
      return;
    }

    // CREATE mode, JSONata — nothing to review yet, so always generate immediately with no
    // user interaction.
    this.drawerTitle = 'Generate Substitutions';
    this.chatConfig = {
      ...this.chatConfig,
      title: this.drawerTitle,
      welcomeText: "Generating substitutions for this mapping based on its source and target templates. " +
        "This starts automatically — no action needed."
    };
    this.newMessage = this.buildGenerateMessage();
    await this.sendMessage();
  }

  /**
   * "I want to provide a sample target payload" suggestion prompt (Smart Function, CREATE mode).
   * Asks the agent to wait for the user's next message — a pasted JSON sample — before
   * generating, rather than proceeding on generic assumptions.
   */
  private buildProvideSampleMessage(): string {
    return "I'd like to provide a sample of the expected target payload before you generate the" +
      " Smart Function. Ask me to paste it, then wait for my next message and use it as the" +
      " authoritative shape to produce — do not generate code yet.\n";
  }

  private buildReviewMessage(): string {
    const direction = this.mapping.direction ?? 'INBOUND';
    const targetAPI = this.mapping.targetAPI ?? '';

    if (this.isCodeMapping) {
      return `Review the existing ${direction} Smart Function` +
        (targetAPI ? ` (target: ${targetAPI})` : '') +
        " and suggest improvements.\n";
    }
    return `Review the existing substitutions for this ${direction}` +
      (targetAPI ? ` ${targetAPI}` : '') + " mapping" +
      " and suggest improvements.\n";
  }

  /**
   * Grounding context re-sent alongside every user message (per AgentChatComponent's
   * groundingContextProvider contract) instead of being embedded in the visible chat message.
   * The mapping JSON can run to dozens of lines (managed-object metadata, self links, etc.) and
   * rendering it inline made the very first "message" in the chat an unreadable wall of raw JSON —
   * this keeps the mapping data available to the agent without cluttering the transcript.
   */
  private buildMappingContext(): string {
    const withSubstitutions = this.isCodeMapping
      ? this.mappingForAI
      : { ...this.mappingForAI, substitutions: this.mapping.substitutions };
    return "[MAPPING_CONTEXT] Current state of the mapping being edited (not written by the user):\n" +
      "```json\n" + JSON.stringify(withSubstitutions, null, 2) + "\n```\n";
  }

  groundingContextProvider = (): string => this.buildMappingContext();

  private buildGenerateMessage(): string {
    const direction = this.mapping.direction ?? 'INBOUND';
    const targetAPI = this.mapping.targetAPI ?? '';

    if (this.isCodeMapping) {
      const isOutbound = direction === 'OUTBOUND';
      // Outbound Smart Functions never have a fixed targetTemplate — it is always "{}" by design,
      // because the function body itself defines whatever message structure the target broker/
      // protocol expects (there's no fixed Cumulocity-side schema to map into, as there is for
      // inbound). Treating that as "missing context" made the AI always stop and ask the user a
      // clarifying question instead of generating code, so the editor stayed empty on every attempt.
      const inboundSample = !isOutbound && targetAPI ? SAMPLE_TEMPLATES_C8Y[targetAPI] : undefined;
      const missingContextHint = isOutbound
        ? "This is an OUTBOUND mapping: there is no fixed targetTemplate/schema — the Smart Function" +
          " itself builds whatever payload structure the target broker/protocol expects. Use the" +
          " sourceTemplate (the Cumulocity object being published) plus the topic/description in the" +
          " mapping context to infer reasonable field names and generate the code directly — do not" +
          " ask the user for a target format before generating.\n"
        : !targetAPI
          ? "The targetAPI is not set — ask the user what kind of Cumulocity object to produce" +
            " (e.g. MEASUREMENT, ALARM, EVENT, INVENTORY) before generating code.\n"
          // INBOUND Smart Functions also always carry an empty targetTemplate ("{}") by design —
          // the function body constructs the Cumulocity API payload itself instead of mapping into
          // a fixed template. Without a concrete example the AI has no shape to aim for, so give it
          // the standard sample payload for the mapping's targetAPI as a reference.
          : inboundSample
            ? `There is no fixed targetTemplate — the Smart Function itself builds the Cumulocity` +
              ` ${targetAPI} API payload from the sourceTemplate fields. A typical ${targetAPI} object` +
              " looks like:\n```json\n" + inboundSample + "\n```\n" +
              " Generate the code directly — do not ask the user for a target format before generating.\n"
            : "";
      return `Generate a ${direction} Smart Function for the following mapping` +
        (targetAPI ? ` (target: ${targetAPI})` : '') + ".\n" +
        missingContextHint;
    }
    const identityTarget = getGenericDeviceIdentifier(this.mapping);
    return `Generate JSONata substitutions for this ${direction}` +
      (targetAPI ? ` ${targetAPI}` : '') + " mapping.\n" +
      `Map the device identity to \`${identityTarget}\` — this mapping has` +
      ` useExternalId=${!!this.mapping.useExternalId} — do not use the other` +
      " `_IDENTITY_` field.\n";
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
    const trimmedContent = typeof content === 'string' ? content.trim() : '';
    if (!trimmedContent) return;

    // A response may contain multiple fenced code blocks — e.g. the agent illustrating
    // alternative approaches before presenting its final answer — so candidates are tried
    // most-recent-first: last ```json-tagged block, then last generic ``` block, then (some
    // agents, e.g. object-type/schema-based ones, omit fences entirely) the raw body itself.
    // Trying only the FIRST match previously grabbed an earlier example/alternative instead of
    // the final answer, which failed to parse and surfaced a spurious error.
    const strictBlocks = [...trimmedContent.matchAll(/```json\s*([\s\S]*?)\s*```/g)].map(m => m[1]);
    const genericBlocks = [...trimmedContent.matchAll(/```(?:json)?\s*([\s\S]*?)\s*```/g)].map(m => m[1]);
    const looksLikeRawJson = trimmedContent.startsWith('[') || trimmedContent.startsWith('{');
    const candidates = [
      ...strictBlocks.reverse(),
      ...genericBlocks.reverse(),
      ...(looksLikeRawJson ? [trimmedContent] : [])
    ];

    for (const candidate of candidates) {
      const trimmedCandidate = candidate?.trim();
      if (!trimmedCandidate) continue;

      try {
        const parsedSubstitutions = JSON.parse(trimmedCandidate);
        if (!Array.isArray(parsedSubstitutions)) continue;

        const isValidSubstitutions = parsedSubstitutions.every(sub =>
          sub.hasOwnProperty('pathSource') &&
          sub.hasOwnProperty('pathTarget') &&
          sub.hasOwnProperty('expandArray')
        );
        if (!isValidSubstitutions) continue;

        // The LLM's JSON block may omit repairStrategy (or emit it as null) — default it here so
        // every downstream consumer (edit modal, persisted mapping) always sees a real value
        // instead of undefined/null.
        this.substitutions = parsedSubstitutions.map(sub => ({
          ...sub,
          repairStrategy: sub.repairStrategy ?? RepairStrategy.DEFAULT
        }));
        this.valid = true;
        return;
      } catch {
        // Not a valid substitutions array — try the next candidate rather than failing outright.
        continue;
      }
    }
    // No candidate parsed into a valid substitutions array — keep the previous substitutions/valid
    // state untouched (common mid-conversation, e.g. a clarifying question with no final answer yet).
  }

}
