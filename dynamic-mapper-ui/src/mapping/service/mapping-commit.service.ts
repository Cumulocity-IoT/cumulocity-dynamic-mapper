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
import { TranslateService } from '@ngx-translate/core';
import { Injectable, Injector, inject } from '@angular/core';
import { FormGroup } from '@angular/forms';
import { AlertService } from '@c8y/ngx-components';
import { gettext } from '@c8y/ngx-components/gettext';
import { ALERT_ACTION_REQUIRED_TIMEOUT, ALERT_SUCCESS_TIMEOUT, DeploymentMapEntry, Direction, isSubstitutionsAsCode, Mapping, StepperConfiguration } from '../../shared';
import { MappingService } from '../core/mapping.service';
import { SubscriptionSelectionService } from '../core/subscription-selection.service';
import { hasPendingSubscriptionChanges, OutboundSubscriptionSelection } from '../step-subscription/outbound-subscription-selection.model';
import { MappingPersistence } from '../../shared/mapping/stepper.model';
import { MappingValidationError } from '../../shared/mapping/mapping-validation-error';
import { isConnectorSelectionEmpty, stringToBase64, hasMappingContentChanged, MappingContentSnapshot, reduceSourceTemplate, stripTemplateMetadataTags } from '../../shared/mapping/util';

/**
 * Saves a mapping from either editor: precondition checks, encoding the working templates and
 * code back onto the mapping, the draft or create write, the deployment write and the refresh.
 * It owns no state and no navigation; callers decide where to go from the {@link CommitResult}.
 * Split out of `MappingStepperService`, which keeps the editors' reactive state.
 */
@Injectable({ providedIn: 'root' })
export class MappingCommitService {
    private readonly mappingService = inject(MappingService);
    private readonly alertService = inject(AlertService);
    private readonly translateService = inject(TranslateService);
    // Resolved on use: most saves carry no subscription change and need none of its dependencies.
    private readonly injector = inject(Injector);

    /**
     * The single commit path for both editors.
     *
     * Owns the precondition checks, the encode step, the `saveDraft` vs `createMapping` branch,
     * the deployment write and the mapping refresh. It owns no navigation: it reports *what
     * happened* and each caller decides where to go — the unified editor maps a
     * {@link CommitBlocker} onto a tab, the stepper's parent decides whether to close the
     * controlled child.
     *
     * `lastUpdate` is deliberately never stamped here. On a draft save it is the
     * optimistic-concurrency token that must be echoed back to the server unchanged; the server
     * assigns a fresh one. Pinned by a test in `mapping-stepper.service.spec.ts`.
     */
    async commitMapping(request: CommitMappingRequest): Promise<CommitResult> {
        const { mapping, deploymentMapEntry, stepperConfiguration, forms } = request;
        const persistence = stepperConfiguration.persistence;

        // A mapping must be bound to at least one connector. The stepper also disables its save
        // button on this condition; the unified editor deliberately does not, so for it this is the
        // check that tells the user why saving was refused and jumps to the Connector tab.
        if (isConnectorSelectionEmpty(deploymentMapEntry)) {
            return {
                status: 'blocked',
                blocker: CommitBlocker.NO_CONNECTOR,
                message: gettext('Select at least one connector before saving.')
            };
        }

        // The form-backed preconditions only apply to the unified editor. The stepper passes no
        // forms because cdk-stepper enforces linear completion, so the user cannot reach Save
        // with an earlier step invalid.
        if (forms) {
            // Belt-and-suspenders: also check the value directly because Formly's group
            // validator strips falsy-keyed errors, so propertyFormly.invalid may be stale.
            if (stepperConfiguration.direction === Direction.INBOUND && !mapping.mappingTopic?.trim()) {
                forms.propertyFormly.get('mappingTopic')?.setErrors({ required: true });
                forms.propertyFormly.get('mappingTopic')?.markAsTouched();
                return { status: 'blocked', blocker: CommitBlocker.MAPPING_TOPIC };
            }
            if (forms.propertyFormly.invalid) {
                forms.propertyFormly.markAllAsTouched();
                return { status: 'blocked', blocker: CommitBlocker.PROPERTY_FORM };
            }

            // Only validate extensionName/eventName when the user-visible selectors are shown.
            // The caller's flag also covers showInternalExtensionNote (PROTOBUF_INTERNAL), where
            // no selectors are rendered and the form controls are always null.
            if (forms.validateExtensionSelection) {
                const extensionName = forms.templateForm.get('extensionName');
                const eventName = forms.templateForm.get('eventName');
                extensionName?.markAsTouched();
                eventName?.markAsTouched();
                if (extensionName?.invalid || eventName?.invalid) {
                    return { status: 'blocked', blocker: CommitBlocker.EXTENSION_SELECTION };
                }
            }
        }

        // Connector-only changes must not create a draft — a draft only tracks content changes —
        // but the deployment still needs to be persisted.
        const deploymentChanged =
            snapshotConnectors(deploymentMapEntry) !== request.initialDeploymentConnectors;

        const encoded = this.encodeMappingForCommit(
            mapping,
            request.sourceTemplate,
            request.targetTemplate,
            request.mappingCode,
            request.initialContentSnapshot,
            stepperConfiguration.allowTemplateExpansion,
            persistence
        );
        if ('error' in encoded) {
            return { status: 'blocked', blocker: CommitBlocker.ENCODING, message: encoded.error };
        }
        const contentChanged = encoded.contentChanged;

        // Tracks whether the mapping row is in a state a deployment can be attached to: true for
        // UPDATE (the row already exists) unless a draft save is attempted and fails, and true
        // for CREATE/COPY only once the create call itself succeeds.
        let mappingPersisted: boolean;
        let persisted: Mapping = mapping;
        try {
            if (persistence === 'draft') {
                mappingPersisted = true;
                if (contentChanged) {
                    // Edits are saved to the line's draft; the running configuration is unchanged
                    // until the draft is published as a version and that version is activated.
                    persisted = await this.mappingService.saveDraft(mapping.id, mapping) ?? mapping;
                }
            } else {
                persisted = await this.mappingService.createMapping(mapping) ?? mapping;
                mappingPersisted = true;
            }
        } catch (error) {
            this.mappingService.refreshMappings(stepperConfiguration.direction);
            // A validation rejection is actionable — the caller shows the problems in a drawer and
            // offers to jump to the offending substitution, instead of a toast that can only be
            // dismissed. Either way the caller keeps its editor open, so the edits survive.
            if (error instanceof MappingValidationError) {
                return { status: 'rejected', error };
            }
            const message = persistence === 'draft'
                ? this.translateService.instant(gettext('Failed to save draft for {{name}}: '), { name: mapping.name }) + error.message
                : this.translateService.instant(gettext('Failed to create mapping {{name}}: '), { name: mapping.name }) + error.message;
            this.alertService.danger(message);
            return { status: 'failed', message };
        }

        // Only persist the deployment once the mapping itself exists, and only when there is
        // something to write: the backend PUT reconciles subscriptions across all connectors
        // live, so a redundant write is not free. See §4 of IMPLEMENTATION-PLAN-COMMIT-MAPPING.md
        // for why this is the editor's condition rather than the stepper parent's.
        let deploymentFailed = false;
        if (mappingPersisted && (deploymentChanged || persistence !== 'draft')) {
            try {
                await this.mappingService.updateDefinedDeploymentMapEntry(deploymentMapEntry);
            } catch (error) {
                // Surfaced rather than swallowed: the deployment was not applied.
                deploymentFailed = true;
                this.alertService.danger(
                    this.translateService.instant(gettext('Failed to deploy mapping {{name}} to connectors: '), { name: mapping.name }) + error.message
                );
            }
        }

        this.mappingService.refreshMappings(stepperConfiguration.direction);

        // After the mapping and its deployment, so a rejected mapping never leaves a subscription
        // behind. Subscriptions are tenant-wide: a failure here does not undo the saved mapping.
        let subscriptionFailed = false;
        const selection = request.subscriptionSelection;
        if (mappingPersisted && deploymentFailed && stepperConfiguration.direction === Direction.OUTBOUND
            && selection && hasPendingSubscriptionChanges(selection)) {
            this.alertService.warning(gettext('The subscriptions were not updated because the mapping could not be deployed. Save again to retry.'));
        }
        if (mappingPersisted && !deploymentFailed && stepperConfiguration.direction === Direction.OUTBOUND
            && selection && hasPendingSubscriptionChanges(selection)) {
            const outcome = await this.injector.get(SubscriptionSelectionService).apply(selection);
            subscriptionFailed = outcome.failed;
        }

        return { status: 'saved', contentChanged, deploymentChanged, deploymentFailed, ...(subscriptionFailed ? { subscriptionFailed } : {}), persisted };
    }

    /**
     * Detects content changes, encodes the working source/target templates and code back onto
     * `mapping`, and guards against a substitutions-as-code mapping with no code. Mutates
     * `mapping` in place (matching both components' prior behavior) and returns either the
     * mutated mapping plus whether its content actually changed, or an `{ error }` for the
     * caller to surface via `raiseAlert` and abort the commit.
     *
     * @param initialContentSnapshot the mapping's snapshot captured at load time (UPDATE mode
     * only) — see `captureMappingContentSnapshot`. `undefined` (CREATE/COPY) always persists.
     */

    encodeMappingForCommit(
        mapping: Mapping,
        sourceTemplate: any,
        targetTemplate: any,
        mappingCode: string | undefined,
        initialContentSnapshot: MappingContentSnapshot | undefined,
        allowTemplateExpansion: boolean,
        persistence: MappingPersistence
    ): { mapping: Mapping; contentChanged: boolean } | { error: string } {
        // Determine what changed before the template-expansion/code-encoding below mutates
        // `mapping` in place.
        const contentChanged = persistence === 'draft' && initialContentSnapshot
            ? hasMappingContentChanged(mapping, sourceTemplate, targetTemplate, mappingCode, initialContentSnapshot)
            : true; // CREATE / COPY always persist

        if (allowTemplateExpansion) {
            mapping.sourceTemplate = reduceSourceTemplate(sourceTemplate, false);
            mapping.targetTemplate = reduceSourceTemplate(targetTemplate, false);
        } else {
            mapping.sourceTemplate = JSON.stringify(sourceTemplate);
            mapping.targetTemplate = JSON.stringify(targetTemplate);
        }

        if (mappingCode) {
            mapping.code = stringToBase64(stripTemplateMetadataTags(mappingCode));
        }

        if (isSubstitutionsAsCode(mapping) && (!mapping.code || mapping.code === null || mapping.code === '')) {
            return { error: 'Internal error in editor. Try again!' };
        }

        return { mapping, contentChanged };
    }
}

/** Which precondition stopped a commit before anything was written. */
export enum CommitBlocker {
    NO_CONNECTOR = 'NO_CONNECTOR',
    MAPPING_TOPIC = 'MAPPING_TOPIC',
    PROPERTY_FORM = 'PROPERTY_FORM',
    EXTENSION_SELECTION = 'EXTENSION_SELECTION',
    /** {@link MappingStepperService.encodeMappingForCommit} returned an error. */
    ENCODING = 'ENCODING'
}

export type CommitResult =
    /** Nothing was written; the caller points the user at the offending input. */
    | { status: 'blocked'; blocker: CommitBlocker; message?: string }
    /** The server rejected the mapping; the caller keeps its editor open and lists the problems. */
    | { status: 'rejected'; error: MappingValidationError }
    /** Anything else went wrong; the alert has already been raised. */
    | { status: 'failed'; message: string }
    /**
     * `persisted` is what the server stored. A caller that stays open **must** adopt it before
     * the next save: the server assigns a fresh `lastUpdate`, and re-sending the old one fails
     * the optimistic-concurrency check with "modified concurrently". It is the request's own
     * mapping when nothing was written (a connector-only change).
     */
    | {
        status: 'saved';
        contentChanged: boolean;
        deploymentChanged: boolean;
        /** The connector assignment PUT failed (already alerted): the editor must not treat the
         *  selection as persisted, or the next Save would skip the retry. */
        deploymentFailed?: boolean;
        /** The mapping is saved but writing the Subscription step's selection failed (already
         *  alerted): an editor that stays open keeps the selection so Save can retry it. */
        subscriptionFailed?: boolean;
        persisted: Mapping;
    };

export interface CommitMappingRequest {
    mapping: Mapping;
    deploymentMapEntry: DeploymentMapEntry;
    /** {@link snapshotConnectors} taken when the editor opened, to detect connector-only changes. */
    initialDeploymentConnectors: string;
    stepperConfiguration: StepperConfiguration;
    sourceTemplate: any;
    targetTemplate: any;
    mappingCode: string | undefined;
    initialContentSnapshot?: MappingContentSnapshot;
    /** Outbound only: what the Subscription step collected. Written after the mapping is saved. */
    subscriptionSelection?: OutboundSubscriptionSelection;
    /**
     * The forms whose validity gates a commit. Omitted by the stepper, whose cdk-stepper already
     * enforces linear completion — passing none means those blockers cannot fire.
     */
    forms?: {
        propertyFormly: FormGroup;
        templateForm: FormGroup;
        /** True when the extensionName/eventName selectors are actually rendered. */
        validateExtensionSelection: boolean;
    };
}

/**
 * What an editor component hands its host so the host can commit: everything
 * {@link MappingStepperService.commitMapping} needs except the deployment, which the host owns.
 * Used by the stepper, whose save button lives in the child but whose persistence lives in the
 * grid parent. It carries no `initialContentSnapshot`: the stepper only creates and copies
 * mappings, which always persist; editing an existing mapping is the unified editor's job.
 */
export type CommitEditorState = Pick<
    CommitMappingRequest,
    'mapping' | 'stepperConfiguration' | 'sourceTemplate' | 'targetTemplate' | 'mappingCode' | 'subscriptionSelection' | 'deploymentMapEntry'
>;

/** The comparable form of a deployment's connector selection, so both editors detect a
 * connector-only change the same way. */
export function snapshotConnectors(deploymentMapEntry: DeploymentMapEntry | undefined): string {
    return JSON.stringify(deploymentMapEntry?.connectors ?? []);
}

/** A success toast for a completed commit: the wording plus how long it should stay up. */
export interface CommitSuccessAlert {
    text: string;
    /** Explicit, because Cumulocity auto-dismisses a detail-less success alert after 3s — too
     *  short for the messages below that ask the user to publish and activate afterwards. */
    timeout: number;
}

/** The success toast for a completed commit, shared so the two editors cannot drift apart. */
export function commitSuccessMessage(
    result: Extract<CommitResult, { status: 'saved' }>,
    mappingName: string,
    persistence: MappingPersistence,
    translateService: Pick<TranslateService, 'instant'>
): CommitSuccessAlert | undefined {
    if (persistence !== 'draft') {
        return {
            text: translateService.instant(gettext('Mapping {{mappingName}} created successfully'), { mappingName }),
            timeout: ALERT_SUCCESS_TIMEOUT
        };
    }
    // A failed deployment write was already reported with an error alert; don't claim it saved.
    const deploymentChanged = result.deploymentChanged && !result.deploymentFailed;
    // The two draft messages instruct a follow-up action, so they get the longer dwell time.
    if (result.contentChanged && deploymentChanged) {
        return {
            text: translateService.instant(gettext('Saved draft and connector assignments for {{mappingName}}. It is not live yet: use Save & Activate, or publish and activate it (Versions).'), { mappingName }),
            timeout: ALERT_ACTION_REQUIRED_TIMEOUT
        };
    }
    if (result.contentChanged) {
        return {
            text: translateService.instant(gettext('Saved draft for {{mappingName}}. It is not live yet: use Save & Activate, or publish and activate it (Versions).'), { mappingName }),
            timeout: ALERT_ACTION_REQUIRED_TIMEOUT
        };
    }
    if (deploymentChanged) {
        return {
            text: translateService.instant(gettext('Connector assignments for {{mappingName}} saved.'), { mappingName }),
            timeout: ALERT_SUCCESS_TIMEOUT
        };
    }
    return undefined;
}
