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

import { inject, Injectable } from '@angular/core';
import { BsModalService, BsModalRef } from 'ngx-bootstrap/modal';
import { AlertService } from '@c8y/ngx-components';
import { gettext } from '@c8y/ngx-components/gettext';
import { firstValueFrom, Subject } from 'rxjs';
import { CodeTemplate, CodeTemplateMap, TemplateType } from '../../configuration/shared/configuration.model';
import { ManageTemplateComponent } from '../../shared/component/code-template/manage-template.component';
import { Direction, SharedService, TransformationType } from '../../shared';
import { MappingStepperService } from './mapping-stepper.service';

/**
 * Code template entry for display in selects
 */
export interface CodeTemplateEntry {
  key: string;
  name: string;
  type: TemplateType;
}

/**
 * Code template item for c8y-select display
 */
export interface CodeTemplateSelectItem {
  label: string;
  value: string;
}

/**
 * Service responsible for managing code templates:
 * - Loading and decoding templates
 * - Filtering by transformation type and direction
 * - Creating new templates via modal
 * - Converting templates for display
 */
@Injectable()
export class CodeTemplateManagementService {
  private readonly bsModalService = inject(BsModalService);
  private readonly alertService = inject(AlertService);
  private readonly sharedService = inject(SharedService);
  private readonly stepperService = inject(MappingStepperService);

  /**
   * Load all code templates from the backend
   */
  async loadCodeTemplates(): Promise<CodeTemplateMap> {
    return await this.sharedService.getCodeTemplates();
  }

  /**
   * Load and decode all code templates
   */
  async loadDecodedCodeTemplates(): Promise<Map<string, CodeTemplate>> {
    return await this.stepperService.loadCodeTemplates();
  }

  /**
   * Filter code template entries by direction and transformation type
   * @param codeTemplates All available code templates
   * @param direction Mapping direction (INBOUND/OUTBOUND)
   * @param transformationType Transformation type
   * @returns Filtered array of template entries
   */
  filterTemplateEntries(
    codeTemplates: CodeTemplateMap | undefined,
    direction: Direction,
    transformationType: TransformationType
  ): CodeTemplateEntry[] {
    if (!codeTemplates) {
      return [];
    }

    const expectedType = `${direction.toString()}_${transformationType.toString()}`;

    return Object.entries(codeTemplates)
      .filter(([key, template]) => template.templateType.toString() === expectedType)
      .map(([key, template]) => ({
        key,
        name: template.name,
        type: template.templateType
      }));
  }

  /**
   * Convert code template entries to c8y-select items
   * @param entries Array of code template entries
   * @returns Array of select items with formatted labels
   */
  convertToSelectItems(entries: CodeTemplateEntry[]): CodeTemplateSelectItem[] {
    return entries.map(item => ({
      label: `${item.name.charAt(0).toUpperCase() + item.name.slice(1)} (${item.type})`,
      value: item.key
    }));
  }

  /**
   * Get code from a template by its ID
   * @param codeTemplatesDecoded Decoded templates map
   * @param templateId ID of the template to retrieve
   * @returns The code string, or undefined if not found
   */
  getTemplateCode(
    codeTemplatesDecoded: Map<string, CodeTemplate>,
    templateId?: string
  ): string | undefined {
    if (!templateId) {
      return undefined;
    }
    const template = codeTemplatesDecoded.get(templateId);
    return template?.code;
  }

  /**
   * Open modal to create a new code template
   * @param currentCode The current code to save as a template
   * @param direction Mapping direction
   * @param transformationType Transformation type
   * @returns Promise that resolves when template is created
   */
  async createCodeTemplate(
    currentCode: string,
    direction: Direction,
    transformationType: TransformationType
  ): Promise<{ success: boolean; templates?: CodeTemplateMap }> {
    const templateType = `${direction.toString()}_${transformationType.toString()}` as TemplateType;

    const initialState = {
      action: 'CREATE',
      codeTemplate: {
        name: `New code template - ${templateType}`,
        templateType
      }
    };

    const modalRef: BsModalRef = this.bsModalService.show(ManageTemplateComponent, { initialState });

    try {
      // Wait for modal to close with result
      const codeTemplate = await firstValueFrom(
        modalRef.content!.closeSubject as Subject<Partial<CodeTemplate>>
      );

      if (codeTemplate) {
        const response = await this.stepperService.createCodeTemplate(
          codeTemplate.name!,
          codeTemplate.description,
          currentCode,
          direction,
          transformationType
        );

        if (response.status >= 200 && response.status < 300) {
          this.alertService.success(gettext('Added new code template.'));
          const templates = await this.loadCodeTemplates();
          return { success: true, templates };
        } else {
          this.alertService.danger(gettext('Failed to create new code template'));
          return { success: false };
        }
      }

      return { success: false };
    } catch (error) {
      console.error('Error creating code template:', error);
      this.alertService.danger(gettext('Failed to create new code template'));
      return { success: false };
    }
  }

  /**
   * Initialize code template data for a component
   * @param direction Mapping direction
   * @param transformationType Transformation type
   * @returns Object with decoded templates, entries, and select items
   */
  async initializeCodeTemplates(
    direction: Direction,
    transformationType: TransformationType
  ): Promise<{
    codeTemplates: CodeTemplateMap;
    codeTemplatesDecoded: Map<string, CodeTemplate>;
    entries: CodeTemplateEntry[];
    selectItems: CodeTemplateSelectItem[];
  }> {
    const codeTemplates = await this.loadCodeTemplates();
    const codeTemplatesDecoded = await this.loadDecodedCodeTemplates();
    const entries = this.filterTemplateEntries(codeTemplates, direction, transformationType);
    const selectItems = this.convertToSelectItems(entries);

    return {
      codeTemplates,
      codeTemplatesDecoded,
      entries,
      selectItems
    };
  }
}
