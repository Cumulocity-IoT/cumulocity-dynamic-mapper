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
  OnDestroy,
  OnInit,
  ViewEncapsulation
} from '@angular/core';
import { DeviceGridService, } from '@c8y/ngx-components/device-grid';
import { Mapping, MappingSubstitution, MappingType, SharedService } from '../../shared';
import { AlertService, BottomDrawerRef } from '@c8y/ngx-components';
import { AIAgentService } from '../core/ai-agent.service';
import { AgentTextDefinition } from '../shared/ai-prompt.model';
import { ServiceConfiguration } from 'src/configuration';
import { filter, from, map, mergeMap, Subject, takeUntil } from 'rxjs';

type AgentDefinitionProps =
  | 'agent'
  | 'provider'
  | 'context'
  | 'agent.variables'
  | 'mcp';

type AgentSettingsTabKey =
  | 'provider'
  | 'context'
  | 'tools'
  | 'systemPrompt'
  | 'advanced'
  | 'test'
  | 'variables';

@Component({
  selector: 'd11r-mapping-ai-prompt',
  templateUrl: 'ai-prompt.component.html',
  styleUrls: ['./ai-prompt.component.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class AIPromptComponent implements OnInit, OnDestroy {

  alertService = inject(AlertService);
  aiAgentService = inject(AIAgentService);
  sharedService = inject(SharedService);

  bottomDrawerRef = inject(BottomDrawerRef);
  deviceGridService = inject(DeviceGridService);

  @Input() mapping: Mapping;

  private _save: (value: MappingSubstitution[]) => void;
  private _cancel: (reason?: any) => void;
  destroy$: Subject<void> = new Subject<void>();


  result: Promise<MappingSubstitution[]> = new Promise((resolve, reject) => {
    this._save = resolve;
    this._cancel = reject;
  });

  substitutions: MappingSubstitution[] = [];
  jsonataAgent: AgentTextDefinition | null = null;
  javaScriptAgent: AgentTextDefinition | null = null;
  agent: AgentTextDefinition | null = null;
  hasIssue = false;
  isLoading = false;
  isLoadingChat = false;
  newMessage = '';
  testVars: string = '';
  serviceConfiguration: ServiceConfiguration;

  async ngOnInit(): Promise<void> {
    console.log(this.mapping);
    this.serviceConfiguration =
      await this.sharedService.getServiceConfiguration();

    from(this.aiAgentService.getAgents())
      .pipe(
        mergeMap(agents => from(agents)), // Convert array to individual emissions
        filter(agent => agent.name === this.serviceConfiguration.jsonataAgent),
        takeUntil(this.destroy$)
      )
      .subscribe(matchingAgent => {
        // update this.tempAgent with the agent that matches 
        this.jsonataAgent = matchingAgent as any;
      });

    from(this.aiAgentService.getAgents())
      .pipe(
        mergeMap(agents => from(agents)), // Convert array to individual emissions
        filter(agent => agent.name === this.serviceConfiguration.javaScriptAgent),
        takeUntil(this.destroy$)
      )
      .subscribe(matchingAgent => {
        // update this.tempAgent with the agent that matches 
        this.javaScriptAgent = matchingAgent as any;
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  save() {
    this._save(this.substitutions);
    this.bottomDrawerRef.close();
  }

  cancel() {
    this._cancel("User canceled");
    this.bottomDrawerRef.close();
  }


  async sendMessage(agentType: MappingType) {
    if (agentType == MappingType.JSON) {
      if (!this.jsonataAgent) {
        return;
      }
      this.agent = this.jsonataAgent;
    } else if (agentType == MappingType.CODE_BASED) {
      if (!this.javaScriptAgent) {
        return;
      }
      this.agent = this.javaScriptAgent;
    }

    if (this.newMessage.trim()) {
      this.isLoadingChat = true;
      if (this.agent.agent.messages === undefined) {
        this.agent.agent.messages = [];
      }

      this.agent.agent.messages.push({
        content: this.newMessage,
        role: 'user',
      });

      try {
        this.agent.agent.variables = JSON.parse(this.testVars);
      } catch (ex) {
        console.log(ex);
        this.alertService.danger('Invalid JSON in test variables');
        // ADDED: Reset isLoading on error
        this.isLoadingChat = false;
        return;
      }
      try {
        const response = await this.aiAgentService.test(this.agent);

        const content =
          typeof response === 'string'
            ? response
            : JSON.stringify(response, null, 2);

        this.agent.agent.messages.push({
          content,
          role: 'assistant',
        });
      } catch (ex) {
        this.alertService.addServerFailure(ex);
      }

      // Clear the input field
      this.newMessage = '';
      this.isLoadingChat = false;
    }
  }

}
