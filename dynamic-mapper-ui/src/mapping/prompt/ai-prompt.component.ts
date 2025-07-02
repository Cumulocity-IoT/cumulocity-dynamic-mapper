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
import { filter, from, mergeMap, Subject, takeUntil } from 'rxjs';

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
  valid: boolean = false;

  result: Promise<MappingSubstitution[]> = new Promise((resolve, reject) => {
    this._save = resolve;
    this._cancel = reject;
  });

  substitutions: MappingSubstitution[] = [];
  agent: AgentTextDefinition | null = null;
  hasIssue = false;
  isLoading = false;
  isLoadingChat = false;
  newMessage = '';
  testVars: string = '';
  serviceConfiguration: ServiceConfiguration;
  agentType: MappingType;

  async ngOnInit(): Promise<void> {
    // console.log(this.mapping);
    this.agentType = this.mapping.mappingType;
    this.serviceConfiguration =
      await this.sharedService.getServiceConfiguration();

    from(this.aiAgentService.getAgents())
      .pipe(
        mergeMap(agents => from(agents)),
        filter(agent => {
          if (this.agentType === MappingType.JSON) {
            return agent.name === this.serviceConfiguration.jsonataAgent;
          } else {
            return agent.name === this.serviceConfiguration.javaScriptAgent;
          }
        }),
        takeUntil(this.destroy$)
      )
      .subscribe(async (matchingAgent) => {
        // update this.agent with the agent that matches 
        this.agent = matchingAgent as any;
        this.testVars = JSON.stringify(
          this.agent?.agent?.variables || {},
        );

        // when the component is loaded I want to automatically 
        // 1. set this.newMessage = [this.mapping.source, this.mapping.target]
        // 2. call sendMessage()
        this.newMessage = JSON.stringify([this.mapping.sourceTemplate, this.mapping.targetTemplate], null, 2);

        // Call sendMessage() automatically
        await this.sendMessage();
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


  async sendMessage() {
    if (!this.agent) {
      return;
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

        this.checkIfResponseContainsSubstitutions(content);
      } catch (ex) {
        this.alertService.addServerFailure(ex);
      }

      // Clear the input field
      this.newMessage = '';
      this.isLoadingChat = false;
    }
  }

  checkIfResponseContainsSubstitutions(content: any) {
    /*
    the content could look like the following:
    I can see you've provided a source JSON with temperature data and a target JSON in Cumulocity measurement format. Let me create the substitutions to map between them:

```json
[
  {
    "pathSource": "temperature",
    "pathTarget": "c8y_TemperatureMeasurement.T.value",
    "expandArray": false
  },
  {
    "pathSource": "unit",
    "pathTarget": "c8y_TemperatureMeasurement.T.unit",
    "expandArray": false
  },
  {
    "pathSource": "time",
    "pathTarget": "time",
    "expandArray": false
  },
  {
    "pathSource": "'c8y_TemperatureMeasurement'",
    "pathTarget": "type",
    "expandArray": false
  }
]
```

These substitutions will:
1. Map the temperature value from the source to the Cumulocity measurement value
2. Map the unit from source to target (note: the source uses "°C" while target expects "C")
3. Map the timestamp directly
4. Set the measurement type as a literal string "c8y_TemperatureMeasurement"
   
now check if the content contains the pattern ```json and then extract the following array and assign it to this.substitutions
 */

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
        // No JSON block found - this might be normal if the AI is still explaining
        console.log('No JSON block found in response');
      }

    } catch (error) {
      this.valid = false;
      console.error('Error parsing substitutions from response:', error);
      this.alertService.danger('Failed to parse substitutions from AI response');
    }
  }

}