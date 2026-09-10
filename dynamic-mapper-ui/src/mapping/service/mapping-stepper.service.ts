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

import { ChangeDetectorRef, Injectable, inject } from '@angular/core';
import { FormGroup } from '@angular/forms';
import { BehaviorSubject, Subject } from 'rxjs';
import { Alert, AlertService } from '@c8y/ngx-components';
import { gettext } from '@c8y/ngx-components/gettext';
import {
    Direction,
    Extension,
    ExtensionEntry,
    ExtensionType,
    getSchema,
    Mapping,
    SAMPLE_TEMPLATES_C8Y,
    StepperConfiguration,
    TransformationType,
    countDeviceIdentifiers,
    createCustomUuid,
    getExternalTemplate,
} from '../../shared';
import { MappingService } from '../core/mapping.service';
import { SharedService } from '../../shared';
import { ExtensionService } from '../../extension';
import { AIAgentService } from '../core/ai-agent.service';
import { CodeTemplate, CodeTemplateMap, ServiceConfiguration, TemplateType, toTemplateType } from '../../configuration/shared/configuration.model';
import { base64ToString, configurationToYaml, stringToBase64, expandC8YTemplate, expandExternalTemplate, hasEsmExport, isCodeOrExtensionTransformation, splitTopicExcludingSeparator, stripTemplateMetadataTags, getTypeOf } from '../shared/util';

@Injectable()
export class MappingStepperService {
    private mappingService = inject(MappingService);
    private sharedService = inject(SharedService);
    private extensionService = inject(ExtensionService);
    private aiAgentService = inject(AIAgentService);
    private alertService = inject(AlertService);

    // Observables
    countDeviceIdentifiers$ = new BehaviorSubject<number>(0);
    isSubstitutionValid$ = new BehaviorSubject<boolean>(false);
    isContentChangeValid$ = new BehaviorSubject<boolean>(true);
    extensionEvents$ = new BehaviorSubject<ExtensionEntry[]>([]);
    isButtonDisabled$ = new BehaviorSubject<boolean>(true);
    sourceCustomMessage$ = new Subject<string>();
    targetCustomMessage$ = new Subject<string>();

    /**
     * Emits whenever a mapping property relevant to template rendering changes
     * (e.g. useExternalId, createNonExistingDevice, eventWithAttachment).
     * Components subscribe once and re-expand their working templates accordingly,
     * removing the need for per-property @Output() events on MappingStepPropertiesComponent.
     */
    readonly mappingPropertyChanged$ = new Subject<Mapping>();

    notifyMappingPropertyChanged(mapping: Mapping): void {
        this.mappingPropertyChanged$.next(mapping);
    }

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
            // Safety net: if no template is available at all, skip evaluation.
            if (!sourceTemplate) {
                return { resultType: '', result: '', valid: true };
            }

            const resultExpression: JSON = await this.mappingService.evaluateExpression(sourceTemplate, path);
            const resultType = getTypeOf(resultExpression);

            if (path && resultType != 'Boolean') {
                throw Error('The filter expression must evaluate to a boolean value: either true or false');
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

    /**
     * @param isBeforeSubstitutionStep whether the caller's current step/tab comes before the
     * substitutions step — a semantic flag rather than a raw index, since this service is shared
     * by two independently-numbered step/tab schemes (the stepper's STEP_* constants and the
     * unified editor's TAB_* constants); comparing a bare index here would silently break if
     * either caller's ordering is ever changed without updating this hardcoded threshold too.
     */
    updateSubstitutionValidity(mapping: Mapping, allowNoDefinedIdentifier: boolean, isBeforeSubstitutionStep: boolean, showCodeEditor: boolean): void {
        const ni = countDeviceIdentifiers(mapping);
        this.countDeviceIdentifiers$.next(ni);

        const isValid = showCodeEditor ||
            (ni == 1 && mapping.direction == Direction.INBOUND) ||
            (ni >= 1 && mapping.direction == Direction.OUTBOUND) ||
            allowNoDefinedIdentifier ||
            isBeforeSubstitutionStep;

        this.isSubstitutionValid$.next(isValid);
    }

    /**
     * Convenience wrapper around {@link updateSubstitutionValidity} for the common case where
     * `allowNoDefinedIdentifier`/`showCodeEditor` are read straight off the caller's own
     * `StepperConfiguration` — the stepper and unified editor previously repeated the same
     * 4-argument call verbatim at every step/tab-transition handler (9 call sites total).
     */
    refreshSubstitutionValidity(mapping: Mapping, stepperConfiguration: StepperConfiguration, isBeforeSubstitutionStep: boolean): void {
        this.updateSubstitutionValidity(mapping, stepperConfiguration.allowNoDefinedIdentifier, isBeforeSubstitutionStep, stepperConfiguration.showCodeEditor);
    }

    expandTemplates(mapping: Mapping, direction: Direction, allowTemplateExpansion?: boolean): {
        sourceTemplate: any;
        targetTemplate: any;
    } {
        const levels: string[] = splitTopicExcludingSeparator(
            direction === Direction.INBOUND
                ? mapping.mappingTopicSample
                : mapping.publishTopicSample,
            false
        );

        const expand = (template: any) => {
            if (!allowTemplateExpansion) return template;
            return direction === Direction.INBOUND
                ? expandExternalTemplate(template, mapping, levels)
                : expandC8YTemplate(template, mapping);
        };

        const expandTarget = (template: any) => {
            if (!allowTemplateExpansion) return template;
            return direction === Direction.INBOUND
                ? expandC8YTemplate(template, mapping)
                : expandExternalTemplate(template, mapping, levels);
        };

        if (direction === Direction.INBOUND) {
            return {
                sourceTemplate: expand(JSON.parse(getExternalTemplate(mapping))),
                targetTemplate: isCodeOrExtensionTransformation(mapping.transformationType)
                    ? {}
                    : expandTarget(JSON.parse(SAMPLE_TEMPLATES_C8Y[mapping.targetAPI]))
            };
        } else {
            return {
                sourceTemplate: expand(JSON.parse(SAMPLE_TEMPLATES_C8Y[mapping.targetAPI])),
                targetTemplate: isCodeOrExtensionTransformation(mapping.transformationType)
                    ? {}
                    : expandTarget(JSON.parse(getExternalTemplate(mapping)))
            };
        }
    }

    expandExistingTemplates(mapping: Mapping, direction: Direction, allowTemplateExpansion?: boolean): {
        sourceTemplate: any;
        targetTemplate: any;
    } {
        const levels: string[] = splitTopicExcludingSeparator(
            direction === Direction.INBOUND
                ? mapping.mappingTopicSample
                : mapping.publishTopicSample,
            false
        );

        const expand = (template: any) => {
            if (!allowTemplateExpansion) return template;
            return direction === Direction.INBOUND
                ? expandExternalTemplate(template, mapping, levels)
                : expandC8YTemplate(template, mapping);
        };

        const expandTarget = (template: any) => {
            if (!allowTemplateExpansion) return template;
            return direction === Direction.INBOUND
                ? expandC8YTemplate(template, mapping)
                : expandExternalTemplate(template, mapping, levels);
        };

        return {
            sourceTemplate: expand(JSON.parse(mapping.sourceTemplate)),
            targetTemplate: isCodeOrExtensionTransformation(mapping.transformationType)
                ? {}
                : expandTarget(JSON.parse(mapping.targetTemplate))
        };
    }

    async loadExtensions(mapping: Mapping): Promise<Map<string, Extension>> {
        const extensions = await this.extensionService.getProcessorExtensions() as Map<string, Extension>;

        if (mapping?.extension?.extensionName && extensions.get(mapping.extension.extensionName)) {
            const entries = Object.values(extensions.get(mapping.extension.extensionName).extensionEntries);
            this.extensionEvents$.next(entries);
        }

        return extensions;
    }

    /**
     * Filters the extension events for the given extension name into extensionEvents$.
     * No-op (with an empty result) if the extension is not currently loaded on the tenant —
     * callers are responsible for surfacing a "not loaded" message to the user in that case.
     */
    selectExtensionName(extensionName: string, extensions: Map<string, Extension>, mapping: Mapping): void {
        const extension = extensions.get(extensionName);
        if (!extension) {
            this.extensionEvents$.next([]);
            return;
        }

        const allEntries = Object.values(extension.extensionEntries);

        // Determine which extension type to filter for based on transformation type
        let targetExtensionType: ExtensionType | null = null;

        if (mapping.transformationType === TransformationType.EXTENSION_JAVA) {
            // EXTENSION_JAVA uses complete extensions (INBOUND or OUTBOUND)
            if (mapping.direction === Direction.INBOUND) {
                targetExtensionType = ExtensionType.EXTENSION_INBOUND;
            } else if (mapping.direction === Direction.OUTBOUND) {
                targetExtensionType = ExtensionType.EXTENSION_OUTBOUND;
            }
        } else if (mapping.extension?.extensionType) {
            // Fall back to the mapping's extension type if set
            targetExtensionType = mapping.extension.extensionType;
        }

        // Filter entries by the determined extension type
        const filteredEntries = targetExtensionType
            ? allEntries.filter(entry => entry.extensionType === targetExtensionType)
            : allEntries;

        this.extensionEvents$.next(filteredEntries);
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
        const templateType = toTemplateType(direction, transformationType);

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
        const agents = await this.aiAgentService.getAIAgents();
        const agentNames = agents.map(agent => agent.name);
        const requiredAgentName = (() => {
            switch (mapping.transformationType) {
                case TransformationType.JSONATA:
                // eslint-disable-next-line @typescript-eslint/no-deprecated -- legacy engine still resolves to the JSONata agent
                case TransformationType.DEFAULT:
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
    }

    // ─── Phase 3: stateless-but-mutating editing operations ────────────────────────────────
    // Shared by MappingStepperComponent and MappingUnifiedEditorComponent, previously duplicated
    // in each. Components keep a thin same-named wrapper that calls these and assigns the
    // result back to its own fields — see
    // docs/planning/IMPLEMENTATION-PLAN-STEPPER-UNIFIED-EDITOR-DEDUP.md, Phase 3.

    /** Filters/clears alert banners the same way both editors do before showing a new one. */
    raiseAlert(alert: Alert): void {
        this.alertService.state.forEach(a => {
            if (a.type === 'info' || a.type === 'warning') this.alertService.remove(a);
        });
        this.alertService.add(alert);
    }

    /** Patches extensionName/eventName/extensionParameter into templateForm on the next microtask (form isn't ready synchronously right after selectExtensionName). */
    patchExtensionFormValues(templateForm: FormGroup, mapping: Mapping, cdr: ChangeDetectorRef): void {
        queueMicrotask(() => {
            templateForm.patchValue({
                extensionName: mapping.extension.extensionName,
                eventName: mapping.extension.eventName,
                extensionParameter: configurationToYaml(mapping.extension.parameter)
            });
            cdr.markForCheck();
        });
    }

    /** Mutates `mapping.extension.extensionName` and refreshes the filtered extension events. */
    applyExtensionNameSelection(extensionName: string, mapping: Mapping, extensions: Map<string, Extension>): void {
        if (!mapping.extension) {
            mapping.extension = {} as any;
        }
        mapping.extension.extensionName = extensionName;
        this.selectExtensionName(extensionName, extensions, mapping);
    }

    /**
     * Mutates `mapping.extension` for the selected event (type/direction/fqnClassName/loaded/
     * message, and — only the first time — the parameter default). Returns whether the event has
     * a parameter block so the caller can update its own `hasExtensionParameter` field, or
     * `undefined` if no matching event entry was found (caller should leave its field unchanged,
     * matching the original per-component behavior).
     */
    applyExtensionEventSelection(
        extensionEvent: string,
        mapping: Mapping,
        extensions: Map<string, Extension>,
        templateForm: FormGroup
    ): boolean | undefined {
        if (!mapping.extension) {
            mapping.extension = {} as any;
        }
        mapping.extension.eventName = extensionEvent;

        if (mapping.extension.extensionName && extensions) {
            const extension = extensions.get(mapping.extension.extensionName);
            if (extension && extension.extensionEntries) {
                const eventEntry = Object.values(extension.extensionEntries)
                    .find(entry => entry.eventName === extensionEvent);

                if (eventEntry) {
                    mapping.extension.extensionType = eventEntry.extensionType;
                    mapping.extension.direction = eventEntry.direction;
                    mapping.extension.fqnClassName = eventEntry.fqnClassName;
                    mapping.extension.loaded = eventEntry.loaded;
                    mapping.extension.message = eventEntry.message;
                    if (!mapping.extension.parameter && eventEntry.parameter) {
                        mapping.extension.parameter = eventEntry.parameter;
                        templateForm.get('extensionParameter')?.setValue(
                            configurationToYaml(eventEntry.parameter), { emitEvent: false });
                    }
                    return !!eventEntry.parameter;
                }
            }
        }
        return undefined;
    }

    /** Mutates `mapping.sourceTemplate`/`targetTemplate` for the newly-selected target API, returning whichever schema (source or target) needs re-computing. */
    applyTargetAPIChange(mapping: Mapping, direction: Direction, changedTargetAPI: string): { schemaSource?: any; schemaTarget?: any } {
        if (direction === Direction.INBOUND) {
            mapping.targetTemplate = isCodeOrExtensionTransformation(mapping.transformationType)
                ? '{}'
                : SAMPLE_TEMPLATES_C8Y[changedTargetAPI];
            mapping.sourceTemplate = getExternalTemplate(mapping);
            return { schemaTarget: getSchema(mapping.targetAPI, mapping.direction, true, false) };
        } else {
            mapping.sourceTemplate = SAMPLE_TEMPLATES_C8Y[changedTargetAPI];
            mapping.targetTemplate = getExternalTemplate(mapping);
            return { schemaSource: getSchema(mapping.targetAPI, mapping.direction, false, false) };
        }
    }

    /** Computes the sample target template shown when the user clicks "Sample target templates". */
    computeSampleTargetTemplate(mapping: Mapping, stepperConfiguration: StepperConfiguration): any {
        if (stepperConfiguration.direction === Direction.INBOUND) {
            if (isCodeOrExtensionTransformation(mapping.transformationType)) {
                return {};
            }
            const template = JSON.parse(SAMPLE_TEMPLATES_C8Y[mapping.targetAPI]);
            return stepperConfiguration.allowTemplateExpansion
                ? expandC8YTemplate(template, mapping)
                : template;
        } else {
            const levels: string[] = splitTopicExcludingSeparator(mapping.mappingTopicSample, false);
            const template = JSON.parse(getExternalTemplate(mapping));
            return stepperConfiguration.allowTemplateExpansion
                ? expandExternalTemplate(template, mapping, levels)
                : template;
        }
    }

    /** Computes the (possibly ESM-export-appended) code for the selected code template, or `undefined` if none is selected. */
    computeCodeFromTemplate(
        codeTemplatesDecoded: Map<string, CodeTemplate>,
        templateId: TemplateType | undefined,
        serviceConfiguration: ServiceConfiguration,
        transformationType: TransformationType
    ): string | undefined {
        const template = codeTemplatesDecoded.get(templateId);
        if (!template) return undefined;

        let code = stripTemplateMetadataTags(template.code);

        if (serviceConfiguration?.supportESM) {
            const exportName = transformationType === TransformationType.SMART_FUNCTION ? 'onMessage' : null;

            if (exportName) {
                const exportStatement = `export { ${exportName} };`;
                if (!hasEsmExport(code, exportName)) {
                    code = code.trimEnd() +
                        '\n\n// ── ESM export (added automatically because Support ESM is enabled) ──────────\n' +
                        exportStatement + '\n';
                }
            }
        }

        return code;
    }

    /** Filters `codeTemplates` down to the entries matching direction/transformationType, plus the derived c8y-select items. */
    computeCodeTemplateEntries(
        codeTemplates: CodeTemplateMap | undefined,
        direction: Direction,
        transformationType: TransformationType
    ): { entries: { key: string; name: string; type: TemplateType }[]; items: { label: string; value: string }[] } {
        if (!codeTemplates) {
            return { entries: [], items: [] };
        }
        const expectedType = `${direction.toString()}_${transformationType.toString()}`;
        const entries = Object.entries(codeTemplates)
            .filter(([, template]) => template.templateType.toString() === expectedType)
            .map(([key, template]) => ({ key, name: template.name, type: template.templateType }));
        const items = entries.map(item => ({
            label: `${item.name.charAt(0).toUpperCase() + item.name.slice(1)} (${item.type})`,
            value: item.key
        }));
        return { entries, items };
    }

    /** Derives the cached c8y-select items for the extension dropdown from the loaded extensions map. */
    computeExtensionItems(extensions: Map<string, Extension>): string[] {
        return Array.from(extensions.keys());
    }

    /** Creates a code template, refetches the code template map, and surfaces a success/error alert — the shared tail of both editors' "create code template" modal flow. */
    async createCodeTemplateAndRefresh(
        name: string,
        description: string,
        code: string,
        direction: Direction,
        transformationType: TransformationType
    ): Promise<CodeTemplateMap> {
        const response = await this.createCodeTemplate(name, description, code, direction, transformationType);
        const codeTemplates = await this.sharedService.getCodeTemplates();

        if (response.status >= 200 && response.status < 300) {
            this.alertService.success(gettext('Added new code template.'));
        } else {
            this.alertService.danger(gettext('Failed to create new code template'));
        }

        return codeTemplates;
    }

    cleanup(): void {
        this.countDeviceIdentifiers$.complete();
        this.isSubstitutionValid$.complete();
        this.extensionEvents$.complete();
        this.sourceCustomMessage$.complete();
        this.targetCustomMessage$.complete();
        this.mappingPropertyChanged$.complete();
    }

}