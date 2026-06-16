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
  Component,
  inject,
  Input,
  OnInit,
  ViewEncapsulation
} from '@angular/core';
import { Mapping, Substitution, MappingType, SharedService, isSubstitutionsAsCode, TransformationType } from '../../shared';
import { AlertService, BottomDrawerRef, CoreModule } from '@c8y/ngx-components';
import { AiChatComponent, AiChatMessageComponent } from '@c8y/ngx-components/ai/ai-chat';
import { AIAgentService } from '../core/ai-agent.service';
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
  imports:[CoreModule, AiChatComponent, AiChatMessageComponent],
  host: { class: 'd-contents' }
})
export class AIPromptComponent implements OnInit {

  private readonly alertService = inject(AlertService);
  private readonly aiAgentService = inject(AIAgentService);
  private readonly sharedService = inject(SharedService);
  private readonly bottomDrawerRef = inject(BottomDrawerRef);

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
  isLoadingChat = false;
  newMessage = '';
  /** Bound to [prompt] on c8y-ai-chat so the textarea stays empty during auto-send. */
  chatInput = '';
  testVars: string = '';
  serviceConfiguration: ServiceConfiguration;
  agentType: MappingType;

  chatConfig = {
    headline: 'AI Assistant',
    welcomeText: '',
    title: 'AI Mapping Assistant',
    placeholder: 'Type your message...',
    sendButtonText: 'Send',
    cancelButtonText: 'Cancel',
    disclaimerText: 'AI-generated responses can contain errors. Verify the details before use.'
  };

  /** Set during ngOnInit — true when the mapping already has code or substitutions */
  isReviewMode = false;
  /** Exposed for the template header */
  drawerTitle = 'AI Mapping Assistant';
  /** In UPDATE mode: true while the user is choosing review-vs-generate */
  awaitingModeChoice = false;
  /** Cached mapping object (without substitutions) ready to send to AI */
  private mappingForAI: any = null;

  // Add getter to check if this is a code-based mapping
  get isCodeMapping(): boolean {
    return isSubstitutionsAsCode(this.mapping);
  }

  async ngOnInit(): Promise<void> {
    // console.log(this.mapping);
    this.agentType = this.mapping.mappingType;
    this.serviceConfiguration =
      await this.sharedService.getServiceConfiguration();

    this.testVars = JSON.stringify(
      this.aiAgent?.agent?.variables || {},
    );

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

    // CREATE mode — always generate
    this.prepareGenerateMessage();
    await this.sendMessage();
  }

  /** Called when the user picks "Review / Refine" in the choice screen */
  async chooseReview(): Promise<void> {
    this.awaitingModeChoice = false;
    this.isReviewMode = true;
    this.prepareReviewMessage();
    await this.sendMessage();
  }

  /** Called when the user picks "Generate new" in the choice screen */
  async chooseGenerate(): Promise<void> {
    this.awaitingModeChoice = false;
    this.isReviewMode = false;
    this.prepareGenerateMessage();
    await this.sendMessage();
  }

  private prepareReviewMessage(): void {
    if (this.isCodeMapping) {
      this.drawerTitle = 'Review / Refine Smart Function';
      this.chatConfig = { ...this.chatConfig, title: 'Review / Refine Smart Function' };
      this.newMessage = "I have an existing Smart Function for the following mapping. " +
        "Please review it and let me know if you see any issues or improvements. " +
        "Feel free to ask me questions about specific changes you'd like to make.\n\n" +
        "Complete Mapping (including existing code):\n\n" +
        "```json\n" + JSON.stringify(this.mappingForAI, null, 2) + "\n```\n";
    } else {
      this.drawerTitle = 'Review / Refine Substitutions';
      this.chatConfig = { ...this.chatConfig, title: 'Review / Refine Substitutions' };
      this.newMessage = "I have existing substitutions for the following mapping. " +
        "Please review them and let me know if you see any issues or improvements. " +
        "Feel free to ask me questions about specific changes you'd like to make.\n\n" +
        "```json\n" + JSON.stringify({ ...this.mappingForAI, substitutions: this.mapping.substitutions }, null, 2) + "\n```\n";
    }
  }

  private prepareGenerateMessage(): void {
    if (this.isCodeMapping) {
      this.drawerTitle = 'Generate Smart Function';
      this.chatConfig = { ...this.chatConfig, title: 'Generate Smart Function' };
      this.newMessage = "Map for the following mapping the source template to the target template:\n\n" +
        "```json\n" + JSON.stringify(this.mappingForAI, null, 2) + "\n```\n";
    } else {
      this.drawerTitle = 'Generate Substitutions';
      this.chatConfig = { ...this.chatConfig, title: 'Generate Substitutions' };
      this.newMessage = "Map for the following mapping the source template to the target template:\n\n" +
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

  async sendMessage() {
    if (!this.aiAgent) {
      return;
    }

    if (this.newMessage) {
      this.isLoadingChat = true;
      if (this.aiAgent.agent.messages === undefined) {
        this.aiAgent.agent.messages = [];
      }

      this.aiAgent.agent.messages.push({
        content: this.newMessage,
        role: 'user',
      });

      try {
        this.aiAgent.agent.variables = JSON.parse(this.testVars);
      } catch (ex) {
        this.alertService.danger('Invalid JSON in test variables');
        this.isLoadingChat = false;
        return;
      }

      try {
        const response = await this.aiAgentService.test(this.aiAgent);

        const content =
          typeof response === 'string'
            ? response
            : JSON.stringify(response, null, 2);

        this.aiAgent.agent.messages.push({
          content,
          role: 'assistant',
        });

        if (this.isCodeMapping) {
          this.checkIfResponseContainsJavaScript(content);
        } else {
          this.checkIfResponseContainsSubstitutions(content);
        }
      } catch (ex) {
        this.alertService.addServerFailure(ex);
      }
      // After the new message is added I want to scroll to the end of the screen
      // Clear the input field
      this.newMessage = '';
      this.isLoadingChat = false;
    }
  }

  checkIfResponseContainsJavaScript(content: any): void {
    try {
      // Look for JavaScript code blocks
      const jsBlockRegex = /```javascript\s*([\s\S]*?)\s*```/;
      const match = content.match(jsBlockRegex);

      if (match && match[1]) {
        // Extract the JavaScript content
        const jsContent = match[1].trim();

        // Validate that it contains a function (basic validation)
        if (jsContent.includes('function') && jsContent.includes('function onMessage')) {
          this.generatedCode = this.applyESMExport(jsContent);
          this.valid = true;
          this.alertService.success('JavaScript code extracted successfully!');
        } else {
          this.valid = false;
          this.alertService.warning('Invalid JavaScript function format found in response');
        }
      } else {
        // Try alternative patterns for code blocks
        const genericCodeRegex = /```(?:js|javascript)?\s*([\s\S]*?)\s*```/;
        const genericMatch = content.match(genericCodeRegex);

        if (genericMatch && genericMatch[1]) {
          const jsContent = genericMatch[1].trim();
          if (jsContent.includes('function')) {
            this.generatedCode = this.applyESMExport(jsContent);
            this.valid = true;
            this.alertService.success('JavaScript code extracted successfully!');
          } else {
            this.valid = false;
            this.alertService.warning('No valid JavaScript function found in response');
          }
        } else {
          this.valid = false;
        }
      }
    } catch (error) {
      this.valid = false;
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

  checkIfResponseContainsSubstitutions(content: any) {
    try {
      // Look for the pattern ```json followed by content and ending with ```
      const jsonBlockRegex = /```json\s*([\s\S]*?)\s*```/;
      const match = content.match(jsonBlockRegex);

      if (match && match[1]) {
        // Extract the JSON content
        const jsonContent = match[1].trim();

        // Parse the JSON array
        const parsedSubstitutions = JSON.parse(jsonContent);

        // Validate that it's an array
        if (Array.isArray(parsedSubstitutions)) {
          // Validate that each item has the expected properties
          const isValidSubstitutions = parsedSubstitutions.every(sub =>
            sub.hasOwnProperty('pathSource') &&
            sub.hasOwnProperty('pathTarget') &&
            sub.hasOwnProperty('expandArray')
          );

          if (isValidSubstitutions) {
            this.substitutions = parsedSubstitutions;
            this.valid = true;
            this.alertService.success('Substitutions extracted successfully!');
          } else {
            this.valid = false;
            this.alertService.warning('Invalid substitution format found in response');
          }
        } else {
          this.valid = false;
          this.alertService.warning('Expected array of substitutions but found different format');
        }
      } else {
        this.valid = false;
      }
    } catch (error) {
      this.valid = false;
      console.error('Error parsing substitutions from response:', error);
      this.alertService.danger('Failed to parse substitutions from AI response');
    }
  }

  getCompatibleMessages() {
    if (!this.aiAgent?.agent?.messages) {
      return [];
    }

    // Filter out messages with incompatible roles
    return this.aiAgent.agent.messages.filter(message =>
      message.role === 'user' ||
      message.role === 'assistant' ||
      message.role === 'system'
    );
  }

  getMessageContent(message: any): string {
    if (!message?.content) {
      return '';
    }

    // If it's already a string, return it
    if (typeof message.content === 'string') {
      return message.content;
    }

    // If it's an array of parts, extract text content
    if (Array.isArray(message.content)) {
      return message.content
        .map(part => {
          if (typeof part === 'string') {
            return part;
          }
          if (part && typeof part === 'object' && 'text' in part) {
            return part.text;
          }
          if (part && typeof part === 'object' && 'content' in part) {
            return part.content;
          }
          return '';
        })
        .filter(text => text.length > 0)
        .join(' ');
    }

    // If it's an object with text property
    if (typeof message.content === 'object' && 'text' in message.content) {
      return message.content.text;
    }

    // Fallback: try to stringify
    try {
      return JSON.stringify(message.content);
    } catch {
      return '[Unable to display content]';
    }
  }

  /**
   * Handles incoming messages from the c8y-ai-chat component
   */
  handleMessage(event: any) {
    this.newMessage = event.content;
    this.sendMessage();
  }

  /**
   * Formats a message for the c8y-ai-chat-message component
   */
  formatMessage(message: any): any {
    let content = this.getMessageContent(message);

    // For assistant messages with object type, wrap in JSON code block
    if (this.aiAgent?.type === 'object' && message.role === 'assistant') {
      content = '```json\n' + content + '\n```';
    }

    return {
      role: message.role,
      content: content,
      timestamp: message.timestamp || new Date().toISOString()
    };
  }

}