/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
import { FormlyFieldConfig } from '@ngx-formly/core';
import { TranslateService } from '@ngx-translate/core';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { BehaviorSubject, map, Observable, shareReplay, Subject } from 'rxjs';
import { Alert, AlertService } from '@c8y/ngx-components';
import { gettext } from '@c8y/ngx-components/gettext';
import {
  Direction,
  Extension,
  ExtensionEntry,
  ExtensionType,
  Feature,
  getSchema,
  Mapping,
  SAMPLE_TEMPLATES_C8Y,
  StepperConfiguration,
  TransformationType,
  countDeviceIdentifiers,
  createCustomUuid,
  getExternalTemplate
} from '../../shared';
import { PROTECTED_TOKENS } from '../../shared/mapping/processor/processor.model';
import { MappingService } from '../core/mapping.service';
import { isJsonObject, JsonSchema, JsonValue, SharedService, Template } from '../../shared';
import { ExtensionService } from '../../extension';
import { AiAgentService, resolveRequiredAgentName } from '../core/ai-agent.service';
import { CodeTemplate, CodeTemplateMap, ServiceConfiguration, TemplateType, decodeCodeTemplates, toTemplateType } from '../../shared/configuration/configuration.model';
import { createCompletionProviderFlowFunction } from '../../shared/mapping/stepper.model';
import { AgentObjectDefinition, AgentTextDefinition } from '../../shared/mapping/ai-prompt.model';
import { StepperViewModel, createStepperViewModel } from '../editor-base/stepper-view.model';
import { buildFilterFormlyFields, buildTemplateForm, EditorSessionCallbacks } from './editor-session.forms';

export type { EditorSessionCallbacks };
import { base64ToString, configurationToYaml, stringToBase64, expandC8YTemplate, expandExternalTemplate, hasEsmExport, isCodeOrExtensionTransformation, splitTopicExcludingSeparator, stripTemplateMetadataTags, getTypeOf } from '../../shared/mapping/util';

@Injectable()
export class MappingStepperService {
    private mappingService = inject(MappingService);
    private sharedService = inject(SharedService);
    private extensionService = inject(ExtensionService);
    private aiAgentService = inject(AiAgentService);
    private alertService = inject(AlertService);
    private translateService = inject(TranslateService);

    // State shared with the host and its steps. Read-only observables: the subjects stay private
    // so there is one place per value that writes it (the methods named next to each).
    private readonly substitutionValid = new BehaviorSubject<boolean>(false);
    private readonly extensionEvents = new BehaviorSubject<ExtensionEntry[]>([]);
    private readonly sourceMessage = new Subject<string>();
    private readonly targetMessage = new Subject<string>();

    /** Written by {@link updateSubstitutionValidity}. */
    readonly isSubstitutionValid$ = this.substitutionValid.asObservable();
    /** Written by {@link loadExtensions}, {@link selectExtensionName} and the event filter. */
    readonly extensionEvents$ = this.extensionEvents.asObservable();
    /** Extra text shown with the source/target expression field; target written by {@link setTargetCustomMessage}. */
    readonly sourceCustomMessage$ = this.sourceMessage.asObservable();
    readonly targetCustomMessage$ = this.targetMessage.asObservable();


    setTargetCustomMessage(message: string): void {
        this.targetMessage.next(message);
    }

    /**
     * Emits whenever a mapping property relevant to template rendering changes
     * (e.g. useExternalId, createNonExistingDevice, eventWithAttachment).
     * Components subscribe once and re-expand their working templates accordingly,
     * removing the need for per-property @Output() events on MappingStepPropertiesComponent.
     */
    private readonly mappingPropertyChanged = new Subject<Mapping>();
    readonly mappingPropertyChanged$ = this.mappingPropertyChanged.asObservable();

    notifyMappingPropertyChanged(mapping: Mapping): void {
        this.mappingPropertyChanged.next(mapping);
    }

    async evaluateSourceExpression(sourceTemplate: Template | undefined, path: string): Promise<{
        resultType: string;
        result: string;
        valid: boolean;
    }> {
        try {
            const r: JsonValue = await this.mappingService.evaluateExpression(sourceTemplate, path);
            return {
                resultType: getTypeOf(r),
                result: JSON.stringify(r, null, 4),
                valid: true
            };
        } catch (error) {
            throw error;
        }
    }

    async evaluateTargetExpression(targetTemplate: Template | undefined, path: string): Promise<{
        resultType: string;
        result: string;
        valid: boolean;
    }> {
        try {
            const r: JsonValue = await this.mappingService.evaluateExpression(targetTemplate, path);
            return {
                resultType: getTypeOf(r),
                result: JSON.stringify(r, null, 4),
                valid: true
            };
        } catch (error) {
            throw error;
        }
    }

    /** True when the template holds at least one key that is not a metadata token. */
    private hasPayloadContent(template: Template | undefined): boolean {
        if (!isJsonObject(template)) {
            return true;
        }
        return Object.keys(template).some(
            key => !(PROTECTED_TOKENS as readonly string[]).includes(key)
        );
    }

    async evaluateFilterExpression(sourceTemplate: Template | undefined, path: string): Promise<{
        resultType: string;
        result: string;
        valid: boolean;
        message?: string;
    }> {
        // Safety net: if no template is available at all, skip evaluation.
        // An unexpanded template (empty, or carrying nothing but metadata tokens) counts
        // as "not available yet": evaluating against it yields undefined and would raise a
        // spurious "must evaluate to a boolean" error while the step is still initializing.
        if (!sourceTemplate || !this.hasPayloadContent(sourceTemplate)) {
            return { resultType: '', result: '', valid: true };
        }

        const resultExpression: JsonValue = await this.mappingService.evaluateExpression(sourceTemplate, path);
        const resultType = getTypeOf(resultExpression);
        const result = JSON.stringify(resultExpression, null, 4);

        // Non-boolean is invalid, but still surface the evaluated value/type so the
        // user can see *what* the expression evaluated to, rather than an empty box.
        if (path && resultType != 'Boolean') {
            return {
                resultType,
                result,
                valid: false,
                message: this.translateService.instant(
                    gettext('The filter expression must evaluate to a boolean value: either true or false')
                )
            };
        }

        return { resultType, result, valid: true };
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

        const isValid = showCodeEditor ||
            (ni == 1 && mapping.direction == Direction.INBOUND) ||
            (ni >= 1 && mapping.direction == Direction.OUTBOUND) ||
            allowNoDefinedIdentifier ||
            isBeforeSubstitutionStep;

        this.substitutionValid.next(isValid);
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
        sourceTemplate: Template | undefined;
        targetTemplate: Template | undefined;
    } {
        const levels: string[] = splitTopicExcludingSeparator(
            direction === Direction.INBOUND
                ? mapping.mappingTopicSample
                : mapping.publishTopicSample,
            false
        );

        const expand = (template: Template | undefined) => {
            if (!allowTemplateExpansion) return template;
            return direction === Direction.INBOUND
                ? expandExternalTemplate(template, mapping, levels)
                : expandC8YTemplate(template, mapping);
        };

        const expandTarget = (template: Template | undefined) => {
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
        sourceTemplate: Template | undefined;
        targetTemplate: Template | undefined;
    } {
        const levels: string[] = splitTopicExcludingSeparator(
            direction === Direction.INBOUND
                ? mapping.mappingTopicSample
                : mapping.publishTopicSample,
            false
        );

        const expand = (template: Template | undefined) => {
            if (!allowTemplateExpansion) return template;
            return direction === Direction.INBOUND
                ? expandExternalTemplate(template, mapping, levels)
                : expandC8YTemplate(template, mapping);
        };

        const expandTarget = (template: Template | undefined) => {
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
            this.extensionEvents.next(entries);
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
            this.extensionEvents.next([]);
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

        this.extensionEvents.next(filteredEntries);
    }

    async loadCodeTemplates(): Promise<Map<string, CodeTemplate>> {
        // Shared with the Code Templates screen so both decode identically — this used to be a
        // second hand-rolled copy that dropped `direction` and forced `defaultTemplate: false`.
        return decodeCodeTemplates(await this.sharedService.getCodeTemplates(), base64ToString);
    }

    async createCodeTemplate(name: string, description: string, code: string, direction: Direction, transformationType: TransformationType) {
        const encodedCode = stringToBase64(code);
        const id = createCustomUuid();
        const templateType = toTemplateType(direction, transformationType);

        return await this.sharedService.createCodeTemplate({
            id,
            name,
            // The dialog leaves the description optional, but the backend requires the field.
            description: description ?? '',
            templateType,
            code: encodedCode,
            internal: false,
            readonly: false,
            defaultTemplate: false
        });
    }

    async checkAIAgentDeployment(mapping: Mapping, serviceConfiguration: ServiceConfiguration): Promise<{
        aiAgentDeployed: boolean;
        aiAgent: AgentObjectDefinition | AgentTextDefinition | null;
        aiCapabilitiesDisabled: boolean;
    }> {
        let agents: AgentTextDefinition[];
        try {
            const settings = await this.aiAgentService.getAISettings(true);
            if (settings.killSwitch) {
                return { aiAgentDeployed: false, aiAgent: null, aiCapabilitiesDisabled: true };
            }
            agents = await this.aiAgentService.getAIAgents(true);
        } catch (error) {
            // A failed availability check must not block the rest of the editor bootstrap —
            // fall back to "no agent deployed" and let the transformation step's own
            // "Generate with AI" affordance simply stay hidden.
            console.error('Failed to check AI agent deployment:', error);
            return { aiAgentDeployed: false, aiAgent: null, aiCapabilitiesDisabled: false };
        }
        const agentNames = agents.map(agent => agent.name);
        const requiredAgentName = resolveRequiredAgentName(mapping.transformationType, serviceConfiguration);

        const hasRequiredAgent = requiredAgentName && agentNames.includes(requiredAgentName);
        const selectedAgent = hasRequiredAgent
            ? agents.find(agent => agent.name === requiredAgentName)
            : null;

        return {
            aiAgentDeployed: agentNames.length > 0 && hasRequiredAgent,
            aiAgent: selectedAgent,
            aiCapabilitiesDisabled: false
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
            mapping.extension = {} as Mapping['extension'];
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
            mapping.extension = {} as Mapping['extension'];
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
    applyTargetAPIChange(mapping: Mapping, direction: Direction, changedTargetAPI: string): { schemaSource?: JsonSchema; schemaTarget?: JsonSchema } {
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
    computeSampleTargetTemplate(mapping: Mapping, stepperConfiguration: StepperConfiguration): Template {
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
        this.completionProviderDisposable?.dispose();
        this.substitutionValid.complete();
        this.extensionEvents.complete();
        this.sourceMessage.complete();
        this.targetMessage.complete();
        this.mappingPropertyChanged.complete();
    }

    // ─── Phase 4: consolidated ngOnInit bootstrap / form & template initialization ─────────
    // Shared by MappingStepperComponent and MappingUnifiedEditorComponent — see
    // docs/planning/IMPLEMENTATION-PLAN-STEPPER-UNIFIED-EDITOR-DEDUP.md, Phase 4.

    // Monaco completion-provider state. Owned here (not per-component) because the service
    // itself is component-scoped (`providers: [MappingStepperService, ...]` on both
    // components), so this is exactly as "per editor instance" as it was as a component field.
    private completionProviderDisposable: { dispose(): void } | undefined;
    private completionProviderRegistering = false;

    /**
     * Registers Monaco's Smart-Function completion provider for the given mapping direction.
     * Guarded against re-entrancy: the unified editor's Monaco instance can be (re)mounted on
     * every tab-switch, and overlapping calls could otherwise race on
     * `completionProviderDisposable` and leak an undisposed provider. This guard is a no-op for
     * the stepper, where the template/code step is never re-entered while a previous
     * registration is still in flight — so it's always safe to apply to both.
     */
    async registerCompletionProvider(direction: Direction): Promise<void> {
        if (this.completionProviderRegistering) {
            return;
        }
        this.completionProviderRegistering = true;
        try {
            if (this.completionProviderDisposable) {
                this.completionProviderDisposable.dispose();
                this.completionProviderDisposable = undefined;
            }
            const monacoModule = await import('monaco-editor');
            const monaco = (monacoModule as unknown as { default?: typeof monacoModule }).default || monacoModule;
            const d1 = createCompletionProviderFlowFunction(monaco, direction);
            this.completionProviderDisposable = { dispose: () => { d1.dispose(); } };
        } finally {
            this.completionProviderRegistering = false;
        }
    }

    /**
     * Runs the full shared `ngOnInit` bootstrap sequence: view model, extension-event item
     * mapping, target/source system labels, editor options, the template form (see
     * {@link buildTemplateForm}), feature/role read-only gating, service configuration, AI-agent
     * deployment check, Formly filter fields, code templates, code-editor help text, and both
     * template schemas.
     *
     * Callers apply the returned fields onto their own state and layer their own
     * boundary-specific setup around this call — see `ngOnInit` in
     * `MappingStepperComponent`/`MappingUnifiedEditorComponent` for what stays outside it
     * (route-data extraction, `initialContentSnapshot`, tab/step index bookkeeping, ...).
     *
     * @param disableExtensionSelectorsWhenHidden stepper-only divergence: when true, the
     * extensionName/eventName controls are additionally disabled while neither source nor
     * target extension selector is shown. Defaults to `false` (the unified editor's behavior).
     */
    async initializeEditorSession(
        mapping: Mapping,
        stepperConfiguration: StepperConfiguration,
        destroy$: Subject<void>,
        callbacks: EditorSessionCallbacks,
        disableExtensionSelectorsWhenHidden = false
    ): Promise<EditorSessionResult> {
        const stepperViewModel = createStepperViewModel(stepperConfiguration);

        const extensionEventItems$ = this.extensionEvents$.pipe(
            map((events: ExtensionEntry[]) =>
                (events || []).map(e => ({
                    label: e.description ? `${e.eventName} — ${e.description}` : e.eventName,
                    value: e.eventName,
                    description: e.description
                }))
            ),
            shareReplay(1)
        );

        const targetSystem = mapping.direction === Direction.INBOUND ? 'Cumulocity' : 'Broker';
        const sourceSystem = mapping.direction === Direction.OUTBOUND ? 'Cumulocity' : 'Broker';

        const editorOptions: EditorComponent['editorOptions'] = {
            minimap: { enabled: true },
            language: 'javascript',
            renderWhitespace: 'none',
            tabSize: 4,
            readOnly: !!stepperConfiguration.readOnly
        };

        const templateForm = buildTemplateForm(
            mapping, stepperConfiguration, stepperViewModel, destroy$, callbacks, disableExtensionSelectorsWhenHidden,
            { isSubstitutionValid$: this.isSubstitutionValid$, mappingPropertyChanged$: this.mappingPropertyChanged$ }
        );

        const feature = await this.sharedService.getFeatures();
        const editorTemplatesReadOnly = !(feature?.userHasMappingAdminRole || feature?.userHasMappingCreateRole);

        const serviceConfiguration = await this.sharedService.getServiceConfiguration();

        const aiResult = await this.checkAIAgentDeployment(mapping, serviceConfiguration);

        const filterFormlyFields = buildFilterFormlyFields(mapping, stepperConfiguration, feature, this.sourceCustomMessage$);

        const codeTemplates = await this.sharedService.getCodeTemplates();
        const codeTemplatesDecoded = await this.loadCodeTemplates();
        const { entries: codeTemplateEntries, items: codeTemplateItems } = this.computeCodeTemplateEntries(
            codeTemplates, stepperConfiguration.direction, mapping.transformationType
        );

        // Both help texts branch on SUBSTITUTION_AS_CODE vs. Smart Function for every caller —
        // previously only the unified editor did this (the stepper hardcoded the Smart Function
        // text), so a legacy SUBSTITUTION_AS_CODE mapping edited via the stepper showed the wrong
        // help text. Fixed as part of this consolidation; see Phase 4 of the plan referenced above.
        // eslint-disable-next-line @typescript-eslint/no-deprecated -- legacy mappings are still opened read-only, so their labels must render
        const isDeprecatedSubstitutionAsCode = mapping.transformationType === TransformationType.SUBSTITUTION_AS_CODE;
        const codeEditorHelp = isDeprecatedSubstitutionAsCode
            ? gettext('JavaScript for creating substitutions...')
            : gettext('JavaScript for creating complete payloads as Smart Functions.');
        const codeEditorLabel = isDeprecatedSubstitutionAsCode
            ? gettext('JavaScript callback for creating substitutions')
            : gettext('JavaScript callback for Smart functions');

        const schemaSource = getSchema(mapping.targetAPI, mapping.direction, false, false);
        const schemaTarget = getSchema(mapping.targetAPI, mapping.direction, true, false);

        return {
            stepperViewModel,
            extensionEventItems$,
            targetSystem,
            sourceSystem,
            editorOptions,
            templateForm,
            editorTemplatesReadOnly,
            feature,
            serviceConfiguration,
            aiAgent: aiResult.aiAgent,
            aiAgentDeployed: aiResult.aiAgentDeployed,
            aiCapabilitiesDisabled: aiResult.aiCapabilitiesDisabled,
            filterFormlyFields,
            codeTemplates,
            codeTemplatesDecoded,
            codeTemplateEntries,
            codeTemplateItems,
            codeEditorHelp,
            codeEditorLabel,
            schemaSource,
            schemaTarget
        };
    }

}

export interface EditorSessionResult {
    stepperViewModel: StepperViewModel;
    extensionEventItems$: Observable<{ label: string; value: string }[]>;
    targetSystem: string;
    sourceSystem: string;
    editorOptions: EditorComponent['editorOptions'];
    templateForm: FormGroup;
    /** Apply by setting `editorOptionsSourceTemplate.readOnly`/`editorOptionsTargetTemplate.readOnly` when true. */
    editorTemplatesReadOnly: boolean;
    feature: Feature;
    serviceConfiguration: ServiceConfiguration;
    aiAgent: AgentObjectDefinition | AgentTextDefinition | null;
    aiAgentDeployed: boolean;
    aiCapabilitiesDisabled: boolean;
    filterFormlyFields: FormlyFieldConfig[];
    codeTemplates: CodeTemplateMap;
    codeTemplatesDecoded: Map<string, CodeTemplate>;
    codeTemplateEntries: { key: string; name: string; type: TemplateType }[];
    codeTemplateItems: { label: string; value: string }[];
    codeEditorHelp: string;
    codeEditorLabel: string;
    schemaSource: JsonSchema | undefined;
    schemaTarget: JsonSchema | undefined;
}