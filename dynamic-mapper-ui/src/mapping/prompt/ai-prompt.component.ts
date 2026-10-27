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
import { Mapping, Substitution, MappingType, RepairStrategy, SAMPLE_TEMPLATES_C8Y, SharedService, isSubstitutionsAsCode, TransformationType, getGenericDeviceIdentifier, Direction } from '../../shared';
import { AlertService, BottomDrawerRef, CoreModule } from '@c8y/ngx-components';
import { AgentChatComponent } from '@c8y/ngx-components/ai/agent-chat';
import { AgentChatConfig, AIMessage, ToolCallPart, AssistantMessageDisplayConfig, ChatHistory, ClientAgentDefinition, Suggestion } from '@c8y/ngx-components/ai';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { gettext } from '@c8y/ngx-components/gettext';
import { TranslateService } from '@ngx-translate/core';
import { AiAgentService, toClientAgentDefinition } from '../core/ai-agent.service';
import {
  AiChatHistoryService,
  codeTargetKey,
  generationInputsKey,
  isStoredResultReusable,
  substitutionsTargetKey
} from '../core/ai-chat-history.service';
import { MappingService } from '../core/mapping.service';
import { MappingTokens } from '../../shared/mapping/processor/processor.model';
import { AgentObjectDefinition, AgentTextDefinition } from '../../shared/mapping/ai-prompt.model';
import { ServiceConfiguration } from '../../configuration';
import { base64ToBytes } from '../../shared/mapping/util';


/** MCP tools (dynamic-mapper-service, MappingResultTools) through which the agents hand over their final result. */
const SUBMIT_SMART_FUNCTION_TOOL = 'submit-smart-function';
const SUBMIT_SUBSTITUTIONS_TOOL = 'submit-substitutions';

@Component({
  selector: 'd11r-mapping-ai-prompt',
  templateUrl: 'ai-prompt.component.html',
  styleUrls: ['./ai-prompt.component.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports:[CoreModule, AgentChatComponent, EditorComponent],
  host: { class: 'd-contents' }
})
export class AiPromptComponent implements OnInit {

  private readonly alertService = inject(AlertService);
  private readonly sharedService = inject(SharedService);
  private readonly bottomDrawerRef = inject(BottomDrawerRef);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly mappingService = inject(MappingService);
  private readonly aiAgentService = inject(AiAgentService);
  private readonly translateService = inject(TranslateService);
  private readonly chatHistoryService = inject(AiChatHistoryService);

  @ViewChild(AgentChatComponent) agentChat?: AgentChatComponent;

  @Input() mapping: Mapping;
  @Input() aiAgent: AgentObjectDefinition | AgentTextDefinition | null;
  /** True when the mapping already exists, so its code/substitutions can be reviewed rather than generated from scratch. */
  @Input() existingMapping = false;

  private _save: (value: Substitution[] | string) => void;
  private _cancel: (reason?: any) => void;
  valid: boolean = false;

  result: Promise<Substitution[] | string> = new Promise((resolve, reject) => {
    this._save = resolve;
    this._cancel = reject;
  });

  substitutions: Substitution[] = [];
  generatedCode: string = '';

  /**
   * Problems found in the AI's most recent answer. Shown in the drawer so the user sees them
   * before applying the result, and can push them straight back to the agent.
   *
   * These are warnings, not a hard gate: Save stays enabled. The checks mirror what the manual
   * substitution editor does (live JSONata evaluation), and that editor treats any evaluation
   * failure as invalid — but an expression can also fail purely against the *sample* template
   * while being right for real payloads, so the user, not this component, makes the final call.
   */
  validationIssues: string[] = [];

  hasIssue = false;
  isLoading = false;
  newMessage = '';
  serviceConfiguration: ServiceConfiguration;
  agentType: MappingType;
  variables: Record<string, unknown> = {};
  clientAgentDefinition?: ClientAgentDefinition;
  aiChatEnabled = false;
  /** Bound to the chat's `telemetry` input; off when the tenant's AI settings opt out of analytics. */
  telemetryEnabled = true;
  aiCapabilitiesDisabled = false;
  aiAvailabilityCheckFailed = false;
  /** Snapshot of the previous conversation about this mapping, if one is being resumed. */
  initialChatHistory?: ChatHistory;
  /** True once the chat holds at least one message; an untouched drawer is never persisted, or the
   *  next open would resume an empty transcript and skip the suggestion chips / auto-generation. */
  private hasMessages = false;
  /** True once the agent submitted its result through a submit-* tool during the current turn. The
   *  reply text is then not scanned for code blocks: it may legitimately contain examples, and the
   *  tool call already carries the authoritative result. Cleared when the next user message is sent. */
  private resultSubmittedViaTool = false;
  /** See generationInputsKey(): what the current conversation is about. */
  private inputsKey?: string;
  /** Key of the code/substitutions the mapping held when this drawer opened; see isStoredResultReusable(). */
  private targetKeyAtOpen = '';

  assistantMessageDisplayConfig: AssistantMessageDisplayConfig = {
    showDefaultToolDetails: 'all',
    experimental_nonFinalStepTextDisplay: 'collapsible-thinking-block',
    // Tool names come from the agent definitions in dynamic-mapper-service/.../resources/agents.
    toolCallConfig: {
      [SUBMIT_SMART_FUNCTION_TOOL]: {
        executingLabel: this.translateService.instant(gettext('Submitting the Smart Function')),
        completedLabel: this.translateService.instant(gettext('Submitted the Smart Function')),
        isShownWithMainAnswer: true
      },
      [SUBMIT_SUBSTITUTIONS_TOOL]: {
        executingLabel: this.translateService.instant(gettext('Submitting the substitutions')),
        completedLabel: this.translateService.instant(gettext('Submitted the substitutions')),
        isShownWithMainAnswer: true
      },
      'evaluate-jsonata-expression': {
        executingLabel: this.translateService.instant(gettext('Testing a JSONata expression')),
        completedLabel: this.translateService.instant(gettext('Tested a JSONata expression'))
      }
    }
  };

  chatConfig: Partial<AgentChatConfig> = {
    headline: this.translateService.instant(gettext('AI Assistant')),
    welcomeText: '',
    title: this.translateService.instant(gettext('AI Mapping Assistant')),
    placeholder: this.translateService.instant(gettext('Type your message...')),
    sendButtonText: this.translateService.instant(gettext('Send')),
    cancelButtonText: this.translateService.instant(gettext('Cancel')),
    disclaimerText: this.translateService.instant(
      gettext('AI-generated responses can contain errors. Verify the details before use.')
    ),
    showCumulativeUsage: true
  };

  /** Read-only preview of `generatedCode`, so the user can check it before pressing Save. */
  readonly codePreviewEditorOptions: EditorComponent['editorOptions'] = {
    language: 'javascript',
    readOnly: true,
    minimap: { enabled: false }
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

    this.aiChatEnabled = true;
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
    // Resume a previous conversation about this same, unchanged mapping instead of starting over.
    this.inputsKey = generationInputsKey(this.mappingForAI);
    this.targetKeyAtOpen = this.currentTargetKey();
    const stored = this.chatHistoryService.get(this.mapping?.identifier);
    if (stored) {
      this.initialChatHistory = stored.history;
      this.hasMessages = true;
      // The extracted result is offered for Save again unless the user changed the code or
      // substitutions by hand since (Save would silently discard that) or the conversation's inputs
      // changed. Otherwise Save in the drawer stays off until the agent produces a new result — the
      // transcript is kept either way, and the agent gets fresh mapping context per message.
      if (isStoredResultReusable(stored, this.inputsKey, this.targetKeyAtOpen)) {
        this.generatedCode = stored.generatedCode;
        this.substitutions = stored.substitutions;
        this.validationIssues = stored.validationIssues;
        this.valid = stored.valid;
      }
    }

    this.cdr.detectChanges();

    if (stored) {
      // The restored transcript already holds the earlier turns, so no suggestion chips and no
      // automatic first message — either would start the conversation again on top of it.
      if (!this.existingMapping) {
        this.drawerTitle = this.isCodeMapping ? 'Generate Smart Function' : 'Generate Substitutions';
      }
      return;
    }

    if (this.existingMapping) {
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
    // Persist (also done on destroy) rather than discard: reopening the drawer after Save should
    // still show the conversation that produced the saved result.
    this.persistSession();
    if (this.isCodeMapping) {
      this._save(this.generatedCode);
    } else {
      this._save(this.substitutions);
    }
    this.bottomDrawerRef.close();
  }

  cancel() {
    this.persistSession();
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
      console.error('AiPromptComponent.sendMessage: #agentChat is not available yet');
      return;
    }
    await this.agentChat.sendMessage({ role: 'user', content: this.newMessage });
    this.newMessage = '';
  }

  /**
   * Called via (onToolResult). Reads the agent's final result straight from the input of its
   * submit-* tool call — structured data, so no parsing of reply text and no guessing which of
   * several code blocks was the final one. Other tools (e.g. evaluate-jsonata-expression) are
   * the agent's own business and ignored here.
   */
  async onToolResult(part: ToolCallPart): Promise<void> {
    if (part.type !== 'tool-result' || part.error) {
      return; // still running, or the server rejected the input — the agent will retry
    }
    if (part.toolName === SUBMIT_SMART_FUNCTION_TOOL && this.isCodeMapping) {
      const code = part.input?.['code'];
      if (typeof code === 'string' && code.includes('function onMessage')) {
        this.resultSubmittedViaTool = true;
        this.acceptSmartFunction(code.trim());
      }
    } else if (part.toolName === SUBMIT_SUBSTITUTIONS_TOOL && !this.isCodeMapping) {
      if (await this.acceptSubstitutions(part.input?.['substitutions'])) {
        this.resultSubmittedViaTool = true;
      }
    } else {
      return;
    }
    this.persistSession();
    this.cdr.detectChanges();
  }

  /** Called via (onMessageFinish) once the agent's response has fully streamed in. */
  async onMessageFinish(message: AIMessage): Promise<void> {
    if (message.role !== 'assistant') {
      return;
    }
    this.hasMessages = true;

    const content = message.content
      .map(part => {
        if (part.type === 'text') return part.text;
        if (part.type === 'object') return part.jsonContent;
        return '';
      })
      .filter(text => text.length > 0)
      .join('\n\n');

    // Preferred channel: the agent called a submit-* tool, handled in onToolResult(). Scraping the
    // text stays as the fallback for agent definitions deployed before those tools existed.
    if (this.resultSubmittedViaTool) {
      this.resultSubmittedViaTool = false;
    } else if (this.isCodeMapping) {
      this.checkIfResponseContainsJavaScript(content);
    } else {
      await this.checkIfResponseContainsSubstitutions(content);
    }
    this.persistSession();
    this.cdr.detectChanges();
  }

  /**
   * Stores the conversation and the result extracted from it so reopening the drawer for the same
   * mapping resumes here. Called after every finished reply, whenever the message list changes and
   * from save()/cancel() — never from ngOnDestroy: the chat component is torn down before this one,
   * so by then its messages may already be cleared and an empty snapshot would replace the real one.
   */
  persistSession(): void {
    if (!this.hasMessages || !this.agentChat || !this.inputsKey || !this.mapping?.identifier) return;
    try {
      this.chatHistoryService.set(this.mapping.identifier, {
        inputsKey: this.inputsKey,
        targetKeyAtOpen: this.targetKeyAtOpen,
        extractedKey: this.extractedKey(),
        history: this.agentChat.saveChatHistory(),
        generatedCode: this.generatedCode,
        substitutions: [...this.substitutions],
        validationIssues: [...this.validationIssues],
        valid: this.valid
      });
    } catch (error) {
      // Persisting is a convenience; never let it break the conversation.
      console.warn('Could not save the AI chat history:', error);
    }
  }

  /** Key of what the mapping currently holds in the place the generated result would go. */
  private currentTargetKey(): string {
    return this.isCodeMapping
      ? codeTargetKey(this.mappingForAI?.code)
      : substitutionsTargetKey(this.mapping.substitutions);
  }

  /** Key of the result extracted from the conversation so far. */
  private extractedKey(): string {
    return this.isCodeMapping ? codeTargetKey(this.generatedCode) : substitutionsTargetKey(this.substitutions);
  }

  /**
   * Called via (onMessagesChange). An empty list is deliberately ignored: the chat component also
   * emits one while it initialises — before it has restored a stored snapshot — so treating it as
   * "the conversation was reset" would delete the very session about to be resumed.
   */
  onMessagesChange(messages: AIMessage[]): void {
    if (messages.length === 0) {
      return;
    }
    this.hasMessages = true;
    if (messages[messages.length - 1]?.role === 'user') {
      this.resultSubmittedViaTool = false;
    }
    this.persistSession();
  }

  /**
   * Extracts JavaScript from the latest assistant response, if present, and marks it valid for saving.
   * A response with no code block (e.g. a clarifying question or acknowledgement) is common in a
   * multi-turn conversation and must NOT revoke a previously extracted, valid `generatedCode` —
   * so this only ever flips `valid` from false to true, never the other way around.
   *
   * A response may contain multiple fenced code blocks — e.g. the agent illustrating an
   * alternative approach before presenting its final `onMessage` implementation — so, mirroring
   * checkIfResponseContainsSubstitutions(), candidates are tried most-recent-first: last
   * ```javascript-tagged block, then last generic ``` block. Using a plain (non-global) .match()
   * here previously always grabbed the FIRST code block, which could be an earlier example
   * instead of the final function and silently produced the wrong generatedCode.
   */
  checkIfResponseContainsJavaScript(content: any): void {
    try {
      const trimmedContent = typeof content === 'string' ? content.trim() : '';
      if (!trimmedContent) return;

      const jsBlocks = [...trimmedContent.matchAll(/```javascript\s*([\s\S]*?)\s*```/g)].map(m => m[1]);
      const genericBlocks = [...trimmedContent.matchAll(/```(?:js|javascript)?\s*([\s\S]*?)\s*```/g)].map(m => m[1]);
      const candidates = [...jsBlocks.reverse(), ...genericBlocks.reverse()];

      for (const candidate of candidates) {
        const jsContent = candidate?.trim();
        if (jsContent?.includes('function') && jsContent.includes('function onMessage')) {
          this.acceptSmartFunction(jsContent);
          return;
        }
      }
      // else: no code block, or none with a recognizable onMessage function — keep the
      // previous generatedCode/valid state untouched rather than disabling Save.
    } catch (error) {
      console.error('Error parsing JavaScript from response:', error);
      this.alertService.danger('Failed to parse JavaScript code from AI response');
    }
  }

  /** Takes the Smart Function source as the current result and marks it valid for saving. */
  private acceptSmartFunction(code: string): void {
    this.generatedCode = this.applyESMExport(code);
    this.validationIssues = this.validateSmartFunctionCode(code);
    this.valid = true;
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
  async checkIfResponseContainsSubstitutions(content: any): Promise<void> {
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
        if (await this.acceptSubstitutions(JSON.parse(trimmedCandidate), true)) return;
      } catch {
        // Not valid JSON — try the next candidate rather than failing outright.
        continue;
      }
    }
    // No candidate parsed into a valid substitutions array — keep the previous substitutions/valid
    // state untouched (common mid-conversation, e.g. a clarifying question with no final answer yet).
  }

  /**
   * Takes `candidate` as the current substitutions and marks them valid for saving, if it is a
   * well-formed array; returns whether it was accepted. Shared by the submit-substitutions tool
   * input and the text-scraping fallback.
   *
   * `requireExpandArray` is for the fallback only: a prose reply can contain unrelated JSON arrays,
   * and `expandArray` being present is what identifies one as substitutions. A tool call has a
   * schema, so it can't be anything else and may omit the (optional) flag.
   */
  private async acceptSubstitutions(candidate: unknown, requireExpandArray = false): Promise<boolean> {
    if (!Array.isArray(candidate) || candidate.length === 0) return false;

    const isValidSubstitutions = candidate.every(sub =>
      sub !== null &&
      typeof sub === 'object' &&
      typeof sub.pathSource === 'string' &&
      typeof sub.pathTarget === 'string' &&
      sub.pathSource.trim() !== '' &&
      sub.pathTarget.trim() !== '' &&
      (!requireExpandArray || Object.prototype.hasOwnProperty.call(sub, 'expandArray'))
    );
    if (!isValidSubstitutions) return false;

    // The LLM's JSON may omit repairStrategy (or emit it as null) — default it here so
    // every downstream consumer (edit modal, persisted mapping) always sees a real value
    // instead of undefined/null. A *hallucinated* strategy name is also coerced back to
    // DEFAULT: nothing between here and the backend checks it, and Jackson rejects an unknown
    // enum constant with an opaque HTTP 400 at save time rather than a mapping-validation
    // error, which is very hard to trace back to the AI-generated substitution that caused it.
    this.substitutions = candidate.map(sub => ({
      ...sub,
      expandArray: !!sub.expandArray,
      repairStrategy: this.coerceRepairStrategy(sub.repairStrategy)
    }));
    this.validationIssues = await this.validateSubstitutions(this.substitutions);
    this.valid = true;
    return true;
  }

  /** Maps an LLM-supplied repairStrategy onto the enum, falling back to DEFAULT when it is
   *  missing, null, or not a real strategy name. */
  private coerceRepairStrategy(value: unknown): RepairStrategy {
    return typeof value === 'string' && Object.values(RepairStrategy).includes(value as RepairStrategy)
      ? (value as RepairStrategy)
      : RepairStrategy.DEFAULT;
  }

  /**
   * Runs the same JSONata evaluation the manual substitution editor uses to set
   * `sourceExpression.valid` / `targetExpression.valid`, plus the device-identity rule the
   * generation prompt asks for but nothing downstream enforces.
   *
   * Applying an AI-generated set previously went straight to `replaceAllSubstitutions()` /
   * `addSubstitution()`, neither of which validates — `isSubstitutionValid()` only ever gated
   * the manual editor's Add button. So a broken expression saved and activated cleanly and
   * first failed per-message at runtime, eventually auto-deactivating the mapping.
   */
  private async validateSubstitutions(substitutions: Substitution[]): Promise<string[]> {
    const issues: string[] = [];
    const sourceTemplate = this.mappingForAI?.sourceTemplate;
    const targetTemplate = this.mappingForAI?.targetTemplate;

    for (const [index, sub] of substitutions.entries()) {
      const label = `Substitution ${index + 1} [${sub.pathTarget}]`;

      const sourceIssue = await this.evaluationIssue(sourceTemplate, sub.pathSource);
      if (sourceIssue) {
        issues.push(`${label}: source expression "${sub.pathSource}" did not evaluate against the source template — ${sourceIssue}`);
      }

      const targetIssue = await this.evaluationIssue(targetTemplate, sub.pathTarget);
      if (targetIssue) {
        issues.push(`${label}: target path "${sub.pathTarget}" is not a valid expression — ${targetIssue}`);
      }
    }

    const identityIssue = this.identityTokenIssue(substitutions);
    if (identityIssue) {
      issues.push(identityIssue);
    }
    issues.push(...this.duplicateTargetIssues(substitutions));
    return issues;
  }

  /**
   * Two substitutions writing the same target are never both meaningful, and which one survives
   * depends on where the generated set is applied — the bulk replace used by the transformation
   * step keeps both, while the stepper's per-item add silently collapses them.
   */
  private duplicateTargetIssues(substitutions: Substitution[]): string[] {
    const seen = new Set<string>();
    const duplicates = new Set<string>();
    for (const sub of substitutions) {
      if (seen.has(sub.pathTarget)) duplicates.add(sub.pathTarget);
      seen.add(sub.pathTarget);
    }
    return [...duplicates].map(
      pathTarget => `More than one substitution writes to "${pathTarget}" — only one of them will take effect.`
    );
  }

  /** Returns the error message if evaluating `path` against `template` fails, else null. */
  private async evaluationIssue(template: any, path: string): Promise<string | null> {
    if (!path || template === undefined) return null;
    try {
      await this.mappingService.evaluateExpression(template, path);
      return null;
    } catch (error) {
      return error?.message ?? 'unknown error';
    }
  }

  /**
   * The mapping's `useExternalId` decides which identity token is correct, and the generation
   * prompt says which one to use — but both the frontend and backend validators accept either,
   * so picking the wrong one is silently allowed and resolves the wrong device.
   */
  private identityTokenIssue(substitutions: Substitution[]): string | null {
    const expected = getGenericDeviceIdentifier(this.mapping);
    const other = expected.endsWith('externalId')
      ? `${MappingTokens.IDENTITY}.c8ySourceId`
      : `${MappingTokens.IDENTITY}.externalId`;
    const isInbound = (this.mapping.direction ?? Direction.INBOUND) === Direction.INBOUND;

    const usesWrongToken = substitutions.some(sub => {
      const path = isInbound ? sub.pathTarget : sub.pathSource;
      return typeof path === 'string' && path.includes(other) && !path.includes(expected);
    });
    return usesWrongToken
      ? `The device identity is mapped via "${other}", but this mapping has useExternalId=${!!this.mapping.useExternalId}, so it must use "${expected}".`
      : null;
  }

  /**
   * Syntax-checks generated Smart Function code. Nothing else does: the backend persists
   * `code` untouched, and the only compile is an opportunistic warm-up on activation that runs
   * on a virtual thread and swallows failures into a log line — so broken code saves *and*
   * activates silently and first fails per message.
   */
  private validateSmartFunctionCode(code: string): string[] {
    // `new Function` parses a function *body*, so module syntax has to go first — the agent may
    // already have emitted the ESM export that applyESMExport() would otherwise add. The backend
    // strips the same constructs via JavaScriptModuleStripper before compiling.
    const plainScript = code
      .replace(/^\s*export\s+\{[^}]*\}\s*;?\s*$/gm, '')
      .replace(/^\s*import\s+.*$/gm, '')
      .replace(/^\s*export\s+(?=(?:function|const|let|var|class)\b)/gm, '');

    try {
      new Function(plainScript);
      return [];
    } catch (error) {
      if (error instanceof SyntaxError) {
        return [`The generated code is not valid JavaScript: ${error.message}. It was most likely truncated before the function was complete — ask the agent to regenerate it.`];
      }
      // Anything else (e.g. a Content-Security-Policy block on Function()) means the check could
      // not run at all, which says nothing about the code — so report nothing.
      return [];
    }
  }

  /** Sends the detected problems back to the agent as a follow-up turn. */
  async askAIToFixIssues(): Promise<void> {
    if (!this.validationIssues.length) return;
    const subject = this.isCodeMapping ? 'Smart Function' : 'substitutions';
    this.newMessage =
      `The ${subject} you generated have the following problems:\n` +
      this.validationIssues.map(issue => `- ${issue}`).join('\n') +
      `\nPlease correct them and return the complete corrected ${subject}.\n`;
    await this.sendMessage();
  }

}
