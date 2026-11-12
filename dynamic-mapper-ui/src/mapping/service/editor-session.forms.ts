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
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { gettext } from '@c8y/ngx-components/gettext';
import { debounceTime, distinctUntilChanged, Observable, Subject, takeUntil } from 'rxjs';
import { Direction, Feature, JsonValue, Mapping, StepperConfiguration } from '../../shared';
import { StepperViewModel } from '../editor-base/stepper-view.model';
import { configurationToYaml, expandC8YTemplate, yamlToConfiguration } from '../../shared/mapping/util';

/** A c8y-select value: a plain string or a `{ value, label }` object. */
type SelectValue = string | { value?: string; label?: string } | null | undefined;

/** The reactive state the session forms read; supplied by the service that owns it. */
export interface EditorSessionState {
    isSubstitutionValid$: Observable<boolean>;
    mappingPropertyChanged$: Observable<Mapping>;
}

/** Callbacks {@link MappingStepperService.initializeEditorSession} needs into the caller's live
 * (mutable-over-time) state — see {@link buildTemplateForm}'s doc comment
 * for why these can't just be captured values. */
export interface EditorSessionCallbacks {
    onSelectExtensionName: (extensionName: string) => void;
    onSelectExtensionEvent: (extensionEvent: string) => void;
    getSourceTemplate: () => JsonValue;
    setSourceTemplate: (template: JsonValue) => void;
}

/**
 * Builds the `filterMapping` Formly field group used by the Select Templates step/tab.
 * Only consumed by `MappingStepperService.initializeEditorSession`.
 */
export function buildFilterFormlyFields(
    mapping: Mapping,
    stepperConfiguration: StepperConfiguration,
    feature: Feature,
    sourceCustomMessage$: Observable<string>
): FormlyFieldConfig[] {
    return [
        {
            fieldGroup: [
                {
                    key: 'filterMapping',
                    type: 'd11r-jsonata-input',
                    wrappers: ['c8y-form-field'],
                    props: {
                        label: gettext('Filter execution mapping'),
                        class: 'input-sm',
                        disabled: stepperConfiguration.readOnly ||
                            !stepperConfiguration.allowDefiningSubstitutions ||
                            (!feature?.userHasMappingAdminRole && !feature?.userHasMappingCreateRole),
                        placeholder: '$exists(c8y_TemperatureMeasurement)',
                        description: mapping.direction === Direction.INBOUND
                            ? gettext('Optional. To drop repeated messages, use $not($isDuplicate(key, seconds)), for example $not($isDuplicate(deviceId & "|" & time, 60)).')
                            : gettext('This expression is required...'),
                        required: mapping.direction === Direction.OUTBOUND,
                        // $isDuplicate works in the inbound filter only; see DYNAMIC_MAPPER_FUNCTIONS
                        mapperFunctions: mapping.direction === Direction.INBOUND,
                        customMessage: sourceCustomMessage$
                    },
                    hooks: {
                        onInit: (_field: FormlyFieldConfig) => {
                            // valueChanges is subscribed inside MappingTemplateStepComponent via ngOnChanges
                        }
                    }
                }
            ]
        }
    ];
}

/**
 * Builds the `templateForm` FormGroup (extensionName/eventName/extensionParameter/
 * sampleTargetTemplatesButton) and wires all of its `valueChanges`/state subscriptions.
 * Only consumed by `MappingStepperService.initializeEditorSession`.
 *
 * The `callbacks` indirection (rather than capturing `mapping`/`extensions`/`sourceTemplate`
 * values directly) matters for two of these subscriptions specifically:
 * - `extensionName`/`eventName` changes must resolve against the CALLER's *current*
 *   `this.extensions` map at the time the user picks a value, not the map that happened to
 *   be loaded when the form was built — the component reloads/reassigns `extensions` later
 *   (e.g. on entering General Settings), so a captured reference would go stale.
 * - `mappingPropertyChanged$` fires at an arbitrary future time and must read/write the
 *   caller's *current* `sourceTemplate` field (reassigned repeatedly over the editor's
 *   session), which a captured value can't do — hence the getter/setter pair.
 */
export function buildTemplateForm(
    mapping: Mapping,
    stepperConfiguration: StepperConfiguration,
    stepperViewModel: StepperViewModel,
    destroy$: Subject<void>,
    callbacks: EditorSessionCallbacks,
    disableExtensionSelectorsWhenHidden: boolean,
    state: EditorSessionState
): FormGroup {
    const extensionSelectorsHidden = disableExtensionSelectorsWhenHidden &&
        !(stepperViewModel.showExtensionSelectorsSource || stepperViewModel.showExtensionSelectorsTarget);

    const templateForm = new FormGroup({
        extensionName: new FormControl({
            value: mapping?.extension?.extensionName,
            disabled: stepperConfiguration.readOnly || extensionSelectorsHidden
        }, Validators.required),
        eventName: new FormControl({
            value: mapping?.extension?.eventName,
            disabled: stepperConfiguration.readOnly || extensionSelectorsHidden
        }, Validators.required),
        extensionParameter: new FormControl({
            value: configurationToYaml(mapping?.extension?.parameter),
            disabled: stepperConfiguration.readOnly
        }),
        sampleTargetTemplatesButton: new FormControl({
            value: !stepperConfiguration.showEditorSource || stepperConfiguration.readOnly,
            disabled: undefined
        })
    });

    templateForm.get('extensionParameter')?.valueChanges
        .pipe(debounceTime(300), takeUntil(destroy$))
        .subscribe(yaml => {
            if (!mapping.extension) {
                mapping.extension = {} as NonNullable<Mapping['extension']>;
            }
            mapping.extension.parameter = yamlToConfiguration(yaml);
        });

    templateForm.get('extensionName')?.valueChanges
        .pipe(distinctUntilChanged(), debounceTime(100), takeUntil(destroy$))
        .subscribe((selected: SelectValue) => {
            // When using simple string arrays, c8y-select binds the string directly
            const extensionName = typeof selected === 'string' ? selected : selected?.value;
            if (extensionName) {
                callbacks.onSelectExtensionName(extensionName);
            }
        });

    templateForm.get('eventName')?.valueChanges
        .pipe(distinctUntilChanged(), debounceTime(100), takeUntil(destroy$))
        .subscribe((selected: SelectValue) => {
            const eventName = typeof selected === 'string' ? selected : selected?.value;
            if (eventName) {
                callbacks.onSelectExtensionEvent(eventName);
            }
        });

    state.isSubstitutionValid$.pipe(takeUntil(destroy$)).subscribe(valid => {
        if (valid) {
            templateForm.setErrors(null);
        } else {
            templateForm.setErrors({ 'incorrect': true });
        }
    });

    state.mappingPropertyChanged$.pipe(takeUntil(destroy$)).subscribe(changedMapping => {
        if (changedMapping.direction === Direction.OUTBOUND) {
            const currentSourceTemplate = callbacks.getSourceTemplate();
            if (currentSourceTemplate) {
                callbacks.setSourceTemplate(expandC8YTemplate(currentSourceTemplate, changedMapping));
            }
        }
    });

    return templateForm;
}
