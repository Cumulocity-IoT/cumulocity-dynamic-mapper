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
  AfterViewChecked,
  Component,
  ElementRef,
  inject,
  Input,
  OnDestroy,
  OnInit,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { DeviceGridService, } from '@c8y/ngx-components/device-grid';
import { Mapping, Substitution, MappingType, SharedService, isSubstitutionsAsCode } from '../../shared';
import { AlertService, BottomDrawerRef } from '@c8y/ngx-components';
import { AIAgentService } from '../core/ai-agent.service';
import { AgentObjectDefinition, AgentTextDefinition } from '../shared/ai-prompt.model';
import { ServiceConfiguration } from '../../configuration';
import { Subject } from 'rxjs';
import { base64ToBytes } from '../shared/util';


@Component({
  selector: 'd11r-mapping-ai-prompt',
  templateUrl: 'ai-prompt.component.html',
  styleUrls: ['./ai-prompt.component.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class AIPromptComponent implements OnInit, OnDestroy, AfterViewChecked {

  @ViewChild('cardInnerScroll', { static: false }) cardInnerScroll: ElementRef;
  private shouldScrollToBottom = false;

  alertService = inject(AlertService);
  aiAgentService = inject(AIAgentService);
  sharedService = inject(SharedService);
  bottomDrawerRef = inject(BottomDrawerRef);
  deviceGridService = inject(DeviceGridService);

  @Input() mapping: Mapping;
  @Input() aiAgent: AgentObjectDefinition | AgentTextDefinition | null;

  private _save: (value: Substitution[] | string) => void;
  private _cancel: (reason?: any) => void;
  destroy$: Subject<void> = new Subject<void>();
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
  testVars: string = '';
  serviceConfiguration: ServiceConfiguration;
  agentType: MappingType;

  // Add getter to check if this is a code-based mapping
  get isCodeMapping(): boolean {
    return this.agentType === MappingType.CODE_BASED || isSubstitutionsAsCode(this.mapping);
  }

  async ngOnInit(): Promise<void> {
    // console.log(this.mapping);
    this.agentType = this.mapping.mappingType;
    this.serviceConfiguration =
      await this.sharedService.getServiceConfiguration();

    this.testVars = JSON.stringify(
      this.aiAgent?.agent?.variables || {},
    );

    if (this.isCodeMapping) {
      // For CODE mappings, include existing JavaScript code if available
      const existingCode = this.extractExistingJavaScriptCode(this.mapping);
      const mappingWithoutSubstitutions = { ...this.mapping };
      delete mappingWithoutSubstitutions.substitutions;
      mappingWithoutSubstitutions.code = existingCode;
      mappingWithoutSubstitutions.sourceTemplate = JSON.parse(mappingWithoutSubstitutions.sourceTemplate);
      mappingWithoutSubstitutions.targetTemplate = JSON.parse(mappingWithoutSubstitutions.targetTemplate);

      // this.newMessage = JSON.stringify({
      //   sourceTemplate: JSON.parse(this.mapping.sourceTemplate),
      //   targetTemplate: JSON.parse(this.mapping.targetTemplate),
      //   existingCode: existingCode,
      //   mappingType: 'JavaScript'
      // }, null, 2);

      this.newMessage = "Map for the following mapping the source template to the target template:\n\n" +
        "**Complete Mapping:**\n\n" +
        "```json\n" +
        JSON.stringify(mappingWithoutSubstitutions, null, 2)
      "\n```\n";
      // +  "**Source:**\n\n" +
      // "```json\n" +
      // JSON.stringify(JSON.parse(this.mapping.sourceTemplate), null, 2) +
      // "\n```\n\n" +
      // "**Target:**\n\n" +
      // "```json\n" +
      // JSON.stringify(JSON.parse(this.mapping.targetTemplate), null, 2) +
      // "\n```\n";
    } else {
      // Include the complete mapping but remove existing substitutions
      const mappingWithoutSubstitutions = { ...this.mapping };
      delete mappingWithoutSubstitutions.substitutions;
      mappingWithoutSubstitutions.sourceTemplate = JSON.parse(mappingWithoutSubstitutions.sourceTemplate);
      mappingWithoutSubstitutions.targetTemplate = JSON.parse(mappingWithoutSubstitutions.targetTemplate);

      this.newMessage = "Map for the following mapping the source template to the target template:\n\n" +
        "```json\n" +
        JSON.stringify(mappingWithoutSubstitutions, null, 2)
        + "\n```\n";
      // + "**Source:**\n\n" +
      // "```json\n" +
      // JSON.stringify(JSON.parse(this.mapping.sourceTemplate), null, 2) +
      // "\n```\n\n" +
      // "**Target:**\n\n" +
      // "```json\n" +
      // JSON.stringify(JSON.parse(this.mapping.targetTemplate), null, 2) +
      // "\n```\n";
    }

    // Call sendMessage() automatically
    await this.sendMessage();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  ngAfterViewChecked(): void {
    if (this.shouldScrollToBottom) {
      this.scrollToBottom();
      this.shouldScrollToBottom = false;
    }
  }

  private scrollToBottom(): void {
    setTimeout(() => {
      try {
        if (this.cardInnerScroll && this.cardInnerScroll.nativeElement) {
          const element = this.cardInnerScroll.nativeElement;
          element.scrollTo({
            top: element.scrollHeight,
            behavior: 'smooth'
          });
          return;
        }

        const cardInnerScrollElement = document.querySelector('.card-inner-scroll');
        if (cardInnerScrollElement) {
          cardInnerScrollElement.scrollTo({
            top: cardInnerScrollElement.scrollHeight,
            behavior: 'smooth'
          });
        }
      } catch (err) {
        console.error('Error scrolling to bottom:', err);
      }
    }, 200);
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
        console.log(ex);
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

        // Set flag to scroll after AI response is added
        this.shouldScrollToBottom = true;

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
        if (jsContent.includes('function') && jsContent.includes('extractFromSource')) {
          this.generatedCode = jsContent;
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
            this.generatedCode = jsContent;
            this.valid = true;
            this.alertService.success('JavaScript code extracted successfully!');
          } else {
            this.valid = false;
            this.alertService.warning('No valid JavaScript function found in response');
          }
        } else {
          this.valid = false;
          console.log('No JavaScript code block found in response');
        }
      }
    } catch (error) {
      this.valid = false;
      console.error('Error parsing JavaScript from response:', error);
      this.alertService.danger('Failed to parse JavaScript code from AI response');
    }
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
        console.log('No JSON block found in response');
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

}