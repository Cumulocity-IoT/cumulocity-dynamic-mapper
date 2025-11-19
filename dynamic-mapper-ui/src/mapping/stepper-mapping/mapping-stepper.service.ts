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

import { Injectable, inject } from '@angular/core';
import { BehaviorSubject, Subject, from } from 'rxjs';
import { map } from 'rxjs/operators';
import * as _ from 'lodash';
import {
    API,
    Direction,
    Extension,
    ExtensionEntry,
    Mapping,
    MappingType,
    SAMPLE_TEMPLATES_C8Y,
    TransformationType,
    countDeviceIdentifiers,
    createCustomUuid,
    getExternalTemplate,
} from '../../shared';
import { MappingService } from '../core/mapping.service';
import { SharedService } from '../../shared';
import { ExtensionService } from '../../extension';
import { AIAgentService } from '../core/ai-agent.service';
import { CodeTemplate, ServiceConfiguration, TemplateType } from '../../configuration/shared/configuration.model';
import { base64ToString, stringToBase64, expandC8YTemplate, expandExternalTemplate, splitTopicExcludingSeparator, getTypeOf } from '../shared/util';

@Injectable()
export class MappingStepperService {
    private mappingService = inject(MappingService);
    private sharedService = inject(SharedService);
    private extensionService = inject(ExtensionService);
    private aiAgentService = inject(AIAgentService);

    // Observables
    countDeviceIdentifiers$ = new BehaviorSubject<number>(0);
    isSubstitutionValid$ = new BehaviorSubject<boolean>(false);
    isContentChangeValid$ = new BehaviorSubject<boolean>(true);
    extensionEvents$ = new BehaviorSubject<ExtensionEntry[]>([]);
    isButtonDisabled$ = new BehaviorSubject<boolean>(true);
    sourceCustomMessage$ = new Subject<string>();
    targetCustomMessage$ = new Subject<string>();

    async evaluateSourceExpression(sourceTemplate: any, path: string): Promise<{
        resultType: string;
        result: string;
        valid: boolean;
    }> {
        try {
            const r: JSON = await this.mappingService.evaluateExpression(sourceTemplate, path);
            return {
                resultType: getTypeOf(r),
                result: JSON.stringify(r, null, 4),
                valid: true
            };
        } catch (error) {
            throw error;
        }
    }

    async evaluateTargetExpression(targetTemplate: any, path: string): Promise<{
        resultType: string;
        result: string;
        valid: boolean;
    }> {
        try {
            const r: JSON = await this.mappingService.evaluateExpression(targetTemplate, path);
            return {
                resultType: getTypeOf(r),
                result: JSON.stringify(r, null, 4),
                valid: true
            };
        } catch (error) {
            throw error;
        }
    }

    async evaluateFilterExpression(sourceTemplate: any, path: string): Promise<{
        resultType: string;
        result: string;
        valid: boolean;
    }> {
        try {
            const resultExpression: JSON = await this.mappingService.evaluateExpression(sourceTemplate, path);
            const resultType = getTypeOf(resultExpression);

            if ((path && resultType != 'Boolean') || (path && resultType == 'Boolean' && !resultExpression)) {
                throw Error('The filter expression must return true');
            }

            return {
                resultType,
                result: JSON.stringify(resultExpression, null, 4),
                valid: true
            };
        } catch (error) {
            throw error;
        }
    }

    updateSubstitutionValidity(mapping: Mapping, allowNoDefinedIdentifier: boolean, currentStepIndex: number, showCodeEditor: boolean): void {
        const ni = countDeviceIdentifiers(mapping);
        this.countDeviceIdentifiers$.next(ni);

        const isValid = showCodeEditor ||
            (ni == 1 && mapping.direction == Direction.INBOUND) ||
            (ni >= 1 && mapping.direction == Direction.OUTBOUND) ||
            allowNoDefinedIdentifier ||
            currentStepIndex < 3;

        this.isSubstitutionValid$.next(isValid);
    }

    expandTemplates(mapping: Mapping, direction: Direction, supportsMessageContext: boolean): {
        sourceTemplate: any;
        targetTemplate: any;
    } {
        const levels: string[] = splitTopicExcludingSeparator(
            direction === Direction.INBOUND
                ? mapping.mappingTopicSample
                : mapping.publishTopicSample,
            false
        );

        const expandSource = (template: any) =>
            direction === Direction.INBOUND
                ? expandExternalTemplate(template, mapping, supportsMessageContext, levels)
                : expandC8YTemplate(template, mapping);

        const expandTarget = (template: any) =>
            direction === Direction.INBOUND
                ? expandC8YTemplate(template, mapping)
                : expandExternalTemplate(template, mapping, supportsMessageContext, levels);

        if (direction === Direction.INBOUND) {
            return {
                sourceTemplate: expandSource(JSON.parse(getExternalTemplate(mapping))),
                targetTemplate: expandTarget(JSON.parse(SAMPLE_TEMPLATES_C8Y[mapping.targetAPI]))
            };
        } else {
            return {
                sourceTemplate: expandSource(JSON.parse(SAMPLE_TEMPLATES_C8Y[mapping.targetAPI])),
                targetTemplate: expandTarget(JSON.parse(getExternalTemplate(mapping)))
            };
        }
    }

    expandExistingTemplates(mapping: Mapping, direction: Direction, supportsMessageContext: boolean): {
        sourceTemplate: any;
        targetTemplate: any;
    } {
        const levels: string[] = splitTopicExcludingSeparator(
            direction === Direction.INBOUND
                ? mapping.mappingTopicSample
                : mapping.publishTopicSample,
            false
        );

        const expandSource = (template: any) =>
            direction === Direction.INBOUND
                ? expandExternalTemplate(template, mapping, supportsMessageContext, levels)
                : expandC8YTemplate(template, mapping);

        const expandTarget = (template: any) =>
            direction === Direction.INBOUND
                ? expandC8YTemplate(template, mapping)
                : expandExternalTemplate(template, mapping, supportsMessageContext, levels);

        return {
            sourceTemplate: expandSource(JSON.parse(mapping.sourceTemplate)),
            targetTemplate: expandTarget(JSON.parse(mapping.targetTemplate))
        };
    }

    async loadExtensions(mapping: Mapping): Promise<Map<string, Extension>> {
        const extensions = await this.extensionService.getProcessorExtensions() as Map<string, Extension>;

        if (mapping?.extension?.extensionName && extensions.get(mapping.extension.extensionName)) {
            this.extensionEvents$.next(
                Object.values(extensions.get(mapping.extension.extensionName)?.extensionEntries)
            );
        }

        return extensions;
    }

    selectExtensionName(extensionName: string, extensions: Map<string, Extension>, mapping: Mapping): void {
        this.extensionEvents$.next(
            Object.values(extensions.get(extensionName).extensionEntries as Map<string, ExtensionEntry>)
                .filter(entry => entry.extensionType == mapping.extension.extensionType)
        );
    }

    async loadCodeTemplates(): Promise<Map<string, CodeTemplate>> {
        const codeTemplates = await this.sharedService.getCodeTemplates();
        const codeTemplatesDecoded = new Map<string, CodeTemplate>();

        Object.entries(codeTemplates).forEach(([key, template]) => {
            try {
                const decodedCode = base64ToString(template.code);
                codeTemplatesDecoded.set(key, {
                    id: key,
                    name: template.name,
                    templateType: template.templateType,
                    code: decodedCode,
                    internal: template.internal,
                    readonly: template.readonly,
                    defaultTemplate: false
                });
            } catch (error) {
                codeTemplatesDecoded.set(key, {
                    id: key,
                    name: template.name,
                    templateType: template.templateType,
                    code: "// Code Template not valid!",
                    internal: template.internal,
                    readonly: template.readonly,
                    defaultTemplate: false
                });
            }
        });

        return codeTemplatesDecoded;
    }

    async createCodeTemplate(name: string, description: string, code: string, direction: Direction, transformationType: TransformationType): Promise<any> {
        const encodedCode = stringToBase64(code);
        const id = createCustomUuid();
        const templateType = `${direction.toString()}_${transformationType.toString()}` as TemplateType;

        return await this.sharedService.createCodeTemplate({
            id,
            name,
            description,
            templateType,
            direction,
            code: encodedCode,
            internal: false,
            readonly: false,
            defaultTemplate: false
        });
    }

    async checkAIAgentDeployment(mapping: Mapping, serviceConfiguration: ServiceConfiguration): Promise<{
        aiAgentDeployed: boolean;
        aiAgent: any;
    }> {
        return from(this.aiAgentService.getAIAgents())
            .pipe(
                map(agents => {
                    const agentNames = agents.map(agent => agent.name);
                    const requiredAgentName = (() => {
                        switch (mapping.transformationType) {
                            case TransformationType.JSONATA:
                                return serviceConfiguration?.jsonataAgent;
                            case TransformationType.SMART_FUNCTION:
                                return serviceConfiguration?.smartFunctionAgent;
                            default:
                                return serviceConfiguration?.javaScriptAgent;
                        }
                    })();

                    const hasRequiredAgent = requiredAgentName && agentNames.includes(requiredAgentName);
                    const selectedAgent = hasRequiredAgent
                        ? agents.find(agent => agent.name === requiredAgentName)
                        : null;

                    return {
                        aiAgentDeployed: agentNames.length > 0 && hasRequiredAgent,
                        aiAgent: selectedAgent
                    };
                })
            )
            .toPromise();
    }

    parseSnoopedTemplate(snoopedTemplate: string): any {
        try {
            return JSON.parse(snoopedTemplate);
        } catch (error) {
            console.warn('The payload was not in JSON format, now wrap it');
            return { message: snoopedTemplate };
        }
    }

    cleanup(): void {
        this.countDeviceIdentifiers$.complete();
        this.isSubstitutionValid$.complete();
        this.extensionEvents$.complete();
        this.sourceCustomMessage$.complete();
        this.targetCustomMessage$.complete();
    }
}