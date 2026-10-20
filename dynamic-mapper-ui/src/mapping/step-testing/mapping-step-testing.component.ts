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
  EventEmitter,
  inject,
  Input,
  OnDestroy,
  OnInit,
  Output,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { AlertService, CoreModule } from '@c8y/ngx-components';
import { gettext } from '@c8y/ngx-components/gettext';
import { TranslateService } from '@ngx-translate/core';
import { BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject, firstValueFrom, ReplaySubject, Subject, takeUntil } from 'rxjs';
import { Content } from 'vanilla-jsoneditor';
import {
  ConfirmationModalComponent,
  ContentChanges,
  Direction,
  JsonEditorComponent,
  Mapping,
  MappingType,
  StepperConfiguration,
  isSubstitutionsAsCode,
  ALERT_INFO_TIMEOUT
} from '../../shared/';
import { DynamicMapperRequest, TestResult, TestContext, MappingTokens } from '../../shared/mapping/processor/processor.model';
import { TestingService } from '../core/testing.service';
import { patchC8YTemplateForTesting, sortObjectKeys } from '../../shared/mapping/util';
import { CollapseModule } from 'ngx-bootstrap/collapse';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { CommonModule } from '@angular/common';

interface TestingModel {
  results: DynamicMapperRequest[];
  request?: any;
  response?: any;
  logs?: string[];
}

/** JSON.stringify with object keys sorted, so two payloads that differ only in key order compare equal. */
function stableStringify(value: unknown): string {
  return JSON.stringify(value, (_key, v) =>
    v && typeof v === 'object' && !Array.isArray(v)
      ? Object.fromEntries(Object.entries(v as Record<string, unknown>).sort(([a], [b]) => a.localeCompare(b)))
      : v
  );
}

@Component({
  selector: 'd11r-mapping-testing',
  templateUrl: 'mapping-step-testing.component.html',
  styleUrls: ['../../shared/mapping/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule, PopoverModule, CollapseModule, JsonEditorComponent]


})
export class MappingStepTestingComponent implements OnInit, OnDestroy {
  @Input() mapping!: Mapping;
  @Input() stepperConfiguration!: StepperConfiguration;
  @Input() updateTestingTemplate!: ReplaySubject<Mapping>;
  @Output() sourceTemplateChanged = new EventEmitter<any>();

  @ViewChild('editorTestingPayload') editorTestingPayload!: JsonEditorComponent;
  @ViewChild('editorTestingRequest') editorTestingRequest!: JsonEditorComponent;
  @ViewChild('editorTestingResponse') editorTestingResponse!: JsonEditorComponent;

  // Template helpers
  readonly Direction = Direction;
  readonly MappingType = MappingType;
  readonly isSubstitutionsAsCode = isSubstitutionsAsCode;

  // Editor configurations
  readonly editorOptionsDefault = {
    mode: 'tree',
  removeModes: ['table'],
    mainMenuBar: true,
    navigationBar: false,
    statusBar: false,
    readOnly: true
  } as const;

  readonly editorOptionsSource = {
    mode: 'tree',
    mainMenuBar: true,
    removeModes: ['table'],
    navigationBar: false,
    statusBar: false,
    readOnly: false
  } as const;

  // Services
  private readonly testingService = inject(TestingService);
  private readonly alertService = inject(AlertService);
  private readonly bsModalService = inject(BsModalService);
  private readonly translateService = inject(TranslateService);
  private readonly destroy$ = new Subject<void>();

  /**
   * Translates a message and fills its {{placeholders}}. Every key is wrapped in gettext() at the call site, so
   * the extractor finds it; the key is the English text (the app's missing-translation handler interpolates it).
   */
  private t(key: string, params?: Record<string, unknown>): string {
    return this.translateService.instant(key, params);
  }

  // State
  testingModel: TestingModel = { results: [] };
  testMapping!: Mapping;
  sourceTemplate: any;
  sourceSystem = '';
  targetSystem = '';
  selectedResult$ = new BehaviorSubject<number>(-1);
  isLoading = false; // Tracks whether a test request is in progress
  /** Which button started the running test, so only that one shows the spinner. */
  runningTest: 'dryRun' | 'liveTest' | undefined;
  showResponse = false; // Controls collapsible response section
  showConsole = true; // Controls collapsible console section (expanded by default)
  currentApi: string | undefined;
  currentPublishTopic: string | undefined;
  /** Context-level, so it comes from TestResult itself rather than the selected request. */
  generatedKey: string | undefined;

  async ngOnInit(): Promise<void> {
    this.initializeMapping();
    try {
      await this.testingService.resetMockCache();
    } catch (error) {
      this.handleError(gettext('Failed to clear cache'), error);
    }
    this.setupSubscriptions();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.selectedResult$.complete();
  }

  // ===== PUBLIC API =====

  async onTestTransformation(): Promise<void> {
    await this.executeTest(false);
  }

  async onSendTest(): Promise<void> {
    await this.executeTest(true);
  }

  async onResetTransformation(): Promise<void> {
    try {
      // Patch a copy rather than mutating this.sourceTemplate in place: the payload editor's
      // [data] input is only re-applied on reference change, and testMapping/mapping.sourceTemplate
      // (the strings actually sent to the backend) must be resynced with the freshly patched id too.
      const patchedTemplate = { ...this.sourceTemplate };
      patchC8YTemplateForTesting(patchedTemplate, this.testMapping);
      this.syncPayload(patchedTemplate, undefined, false);
      this.resetTestingModel();
      this.updateEditors();

      await this.testingService.resetMockCache();
    } catch (error) {
      this.handleError(gettext('Failed to reset transformation'), error);
    }
  }

  onNextTestResult(): void {
    const nextIndex = this.getNextVisibleResultIndex();
    this.displayTestResult(nextIndex);
  }

  onSourceTemplateChanged(content: ContentChanges): void {
    const contentAsJson = this.parseJsonContent(content.updatedContent);
    // The editor can report a change that only re-serializes the same payload (key order,
    // whitespace, or on load). Treating that as an edit would mark the mapping as changed.
    const unchanged = stableStringify(contentAsJson) === stableStringify(this.sourceTemplate);
    this.syncPayload(contentAsJson, this.extractTopicSample(contentAsJson), !unchanged);
  }

  /**
   * True when neither testing action is permitted for this mapping — currently the
   * ANY_PAYLOAD + SMART_FUNCTION combination, which turns off both flags. Every button in the
   * action bar is then permanently dead (no user action can enable them), so the bar is replaced
   * by an explanation rather than showing inert controls.
   */
  get testingUnavailable(): boolean {
    return !this.stepperConfiguration?.allowTestTransformation
      && !this.stepperConfiguration?.allowTestSending;
  }

  get liveTestTitle(): string {
    return this.mapping?.direction === Direction.OUTBOUND
      ? gettext('Publishes the result to the connected broker')
      : gettext('Sends the result to Cumulocity using a test device');
  }

  get testHelpText(): string {
    const dryRun = this.t(gettext('Dry Run runs the mapping without writing anything to your tenant.'));
    if (this.mapping?.direction === Direction.OUTBOUND) {
      return this.t(gettext('Dry Run runs the mapping without sending anything.'))
        + ' ' + this.t(gettext('Live Test publishes the result to the connected broker and needs a mapping that uses an external id and a Dry Run result.'));
    }
    return dryRun + ' '
      + this.t(gettext('Live Test sends the result to Cumulocity using a test device registered as "d11r_liveTest_" + the external id, so the real device is never touched, and needs a mapping that uses an external id and a Dry Run result.'))
      + ' ' + this.t(gettext('Test devices are listed under Monitoring > Test device.'));
  }

  disableTestSending(): boolean {
    return this.liveTestDisabledReason !== undefined;
  }

  /**
   * Why Live Test cannot be clicked right now, or undefined when it can. Shown as the button's tooltip:
   * a disabled button with no explanation leaves the user guessing (a Dry Run that produced no request
   * keeps it disabled for good).
   */
  get liveTestDisabledReason(): string | undefined {
    if (!this.stepperConfiguration.allowTestSending) {
      return gettext('Live Test is not available for this combination of payload type and transformation type');
    }
    if (!this.testMapping.useExternalId) {
      return gettext('Live Test needs a mapping that uses an external ID');
    }
    if (this.testingModel.results.length === 0) {
      return gettext('Run a Dry Run first; Live Test needs its result');
    }
    return undefined;
  }

  /** Dry Run is disabled while a result is shown; say how to get it back. */
  get dryRunTitle(): string {
    return this.testingModel.results.length > 0
      ? gettext('Use Reset Dry Run to run it again')
      : gettext('Runs the mapping without writing anything to your tenant');
  }

  // ===== PRIVATE METHODS =====

  protected requiresRawPayload(): boolean {
    return this.testMapping?.mappingType === MappingType.HEX ||
           this.testMapping?.mappingType === MappingType.FLAT_FILE;
  }

  private initializeMapping(): void {
    // Ensure direction is always set (fallback to INBOUND if not specified)
    this.mapping.direction = this.mapping.direction ?? Direction.INBOUND;
    const isInbound = this.mapping.direction === Direction.INBOUND;
    this.sourceSystem = isInbound ? 'Broker' : 'Cumulocity';
    this.targetSystem = isInbound ? 'Cumulocity' : 'Broker';

    // Initialize testMapping with the full mapping object
    this.updateTestMapping(this.mapping);

    // Initialize testing model with the payload
    this.resetTestingModel();
  }

  private setupSubscriptions(): void {
    this.updateTestingTemplate
      .pipe(takeUntil(this.destroy$))
      .subscribe(mapping => {
        this.updateTestMapping(mapping);
        this.resetTestingModel();
      });
  }

  private updateTestMapping(testMapping: Mapping): void {
    try {
      this.testMapping = testMapping;
      this.sourceTemplate = JSON.parse(testMapping.sourceTemplate);

      if (testMapping.direction === Direction.OUTBOUND) {
        sortObjectKeys(this.sourceTemplate);
      }
    } catch (error) {
      this.handleError(gettext('Failed to update test mapping'), error);
    }
  }

  private resetTestingModel(): void {
    const logs: string[] = this.requiresRawPayload()
      ? ['INFO ' + this.t(gettext('Validate the mapping logic with real payloads. The specific parsing of whitespace and line terminators (CR/LF) may differ from the test environment, potentially altering the results.'))]
      : [];
    this.testingModel = { results: [], request: {}, response: {}, logs };
    this.selectedResult$.next(-1);
    this.currentApi = undefined;
    this.currentPublishTopic = undefined;
    this.generatedKey = undefined;
  }

  private updateEditors(): void {
    // Data is already sorted in displayTestResult(), pass it directly
    this.editorTestingRequest?.set(this.testingModel.request);
    // Handle null response for test mode (don't try to sort null)
    this.editorTestingResponse?.set(this.testingModel.response);
  }

  private getNextVisibleResultIndex(): number {
    const { results } = this.testingModel;
    const current = this.selectedResult$.getValue();
    let nextIndex = current;

    do {
      nextIndex = (nextIndex >= results.length - 1) ? 0 : nextIndex + 1;
    } while (nextIndex !== current && results[nextIndex]?.hidden);

    return nextIndex;
  }

  private displayTestResult(index: number): void {
    this.selectedResult$.next(index);

    const result = this.testingModel.results[index];

    if (result) {
      this.testingModel.request = sortObjectKeys(result.request);
      this.currentApi = result.api;
      this.currentPublishTopic = result.publishTopic;

      if (result.response) {
        this.testingModel.response = sortObjectKeys(result.response);
      } else {
        this.testingModel.response = {};
        const testModeMsg = 'INFO ' + this.t(gettext('No response in test mode. Data operations (MEASUREMENT, EVENT, ALARM) are prepared but not executed. Use "Live Test" to get actual responses.'));
        if (!this.testingModel.logs) {
          this.testingModel.logs = [];
        }
        if (!this.testingModel.logs.includes(testModeMsg)) {
          this.testingModel.logs = [testModeMsg, ...this.testingModel.logs.filter(l => l !== testModeMsg)];
        }
      }
    } else {
      this.testingModel.request = {};
      this.testingModel.response = {};
    }

    this.updateEditors();
  }

  private async executeTest(sendPayload: boolean, assumeDeviceExists = false): Promise<void> {
    this.isLoading = true;
    this.runningTest = sendPayload ? 'liveTest' : 'dryRun';
    try {
      const result = await this.performTest(sendPayload, assumeDeviceExists);
      await this.handleTestResult(result, sendPayload, assumeDeviceExists);

      if (this.testingModel.results.length > 0) {
        this.displayTestResult(0);
      }
    } catch (error) {
      this.handleError(gettext('Test execution failed'), error);
    } finally {
      this.isLoading = false;
      this.runningTest = undefined;
    }
  }


  private parseRequestResponse(req: DynamicMapperRequest): DynamicMapperRequest {
    return {
      ...req,
      request: typeof req.request === 'string' ? JSON.parse(req.request) : req.request,
      response: typeof req.response === 'string' ? JSON.parse(req.response) : req.response
    };
  }

  // Keeps the testing component's own payload state (editor payload and the mapping copy that is
  // sent to the backend) in step.
  // propagate = true additionally writes the payload back into the mapping being edited and tells
  // the host, so that a payload the user deliberately edited here becomes the mapping's source
  // template. Running a test or resetting must NOT propagate: it would rewrite the live mapping
  // (and store the test-only patched device id in it), and the editor would then report unsaved
  // changes although the user changed nothing.
  // topicSample is only provided when called from the editor onChange handler.
  private syncPayload(parsedContent: any, topicSample?: string, propagate = false): void {
    const sourceTemplateStr = JSON.stringify(parsedContent || {});
    this.sourceTemplate = parsedContent;
    this.testMapping = {
      ...this.testMapping,
      sourceTemplate: sourceTemplateStr,
      ...(topicSample ? { mappingTopicSample: topicSample } : {})
    };
    if (propagate) {
      this.mapping.sourceTemplate = sourceTemplateStr;
      this.sourceTemplateChanged.emit(parsedContent);
    }
  }

  private async performTest(sendPayload: boolean, assumeDeviceExists = false): Promise<TestResult> {
    // Always read directly from the editor to capture the latest content,
    // regardless of whether onChange fired (tree-mode edits may not always trigger it)
    this.syncPayload(this.editorTestingPayload?.get() ?? this.sourceTemplate, undefined, false);

    const extractedPayload = this.requiresRawPayload()
      ? this.sourceTemplate?.['payload']
      : this.testMapping.sourceTemplate;

    // Create test context and call remote testing endpoint
    const testContext: TestContext = {
      // "Assume the device exists" applies to this one run only. It used to be written into testMapping,
      // so every later Dry Run (and a Live Test) silently carried it and the dialog never came back.
      mapping: assumeDeviceExists ? { ...this.testMapping, createNonExistingDevice: true } : this.testMapping,
      payload: extractedPayload,
      send: sendPayload
    };

    const result = await this.testingService.testMapping(testContext);

    // Convert request and response from JSON string to object for all items
    this.testingModel.results = result.requests.map(req => this.parseRequestResponse(req));
    this.generatedKey = result.key;
    const staticLogs = this.testingModel.logs?.filter(l => l.startsWith('INFO')) ?? [];
    const warningLogs = (result.warnings ?? []).map(w => `WARNING: ${w}`);
    this.testingModel.logs = [...staticLogs, ...(result.logs ?? []), ...warningLogs];

    return result;
  }

  private async handleTestResult(result: TestResult, sendPayload: boolean, assumeDeviceExists = false): Promise<void> {
    // A device that does not exist is reported either as a warning (flow / extension mappings
    // produce no request) or as an error (substitution mappings fail resolving the source id).
    // Both carry the same marker. For a Dry Run, offer to re-run assuming the device exists.
    // A Live Test never offers this: it runs against a test device, and creating a real device
    // through createNonExistingDevice would leave an untagged device behind.
    const deviceNotFound = [...(result.warnings ?? []), ...(result.errors ?? [])].some(m =>
      m.includes('createNonExistingDevice is disabled')
    );
    // Already enabled: the failure has another cause and re-running with the flag set cannot fix it.
    if (deviceNotFound && !sendPayload && !assumeDeviceExists && !this.testMapping.createNonExistingDevice) {
      const confirmed = await this.showConfirmation(
        gettext('Device does not exist'),
        gettext('The referenced device does not exist and createNonExistingDevice is disabled for this mapping. Do you want to assume for this test that the device exists?'),
        { ok: gettext('Assume device exists'), cancel: gettext('Cancel') }
      );
      if (confirmed) {
        await this.executeTest(sendPayload, true);
        return;
      }
    }

    if (!result.success) {
      const errorLogs = (result.errors ?? []).map(e => `ERROR: ${e}`);
      this.testingModel.logs = [...(this.testingModel.logs ?? []), ...errorLogs];
      return;
    }

    if (sendPayload) {
      this.alertService.add({ text: this.describeSendResult(result), type: 'info', timeout: ALERT_INFO_TIMEOUT });
    }
  }

  /**
   * Says what a successful send created and for which device, e.g.
   * "Live test successful: created alarm 1684457 on device 491713288 (c8y_Serial 862524060507262)."
   * The bare id alone does not tell the user whether it is an alarm, an event or the device itself.
   */
  describeSendResult(result: TestResult): string {
    const first = result.requests?.[0];
    const rawResponse = first?.response;
    const response = typeof rawResponse === 'string' ? this.tryParse(rawResponse) : rawResponse;
    const id = response?.id;
    const api = (first?.api ?? '').toUpperCase();
    const externalId = first?.externalId
      ? ` (${first.externalIdType ? first.externalIdType + ' ' : ''}${first.externalId})`
      : '';

    let text: string;
    if (api === 'INVENTORY') {
      // The created or updated object is the device itself.
      const deviceId = id ?? first?.sourceId;
      text = deviceId
        ? this.t(gettext('Live test successful: device {{deviceId}}{{externalId}} created or updated.'), { deviceId, externalId })
        : this.t(gettext('Live test successful: device created or updated.'));
    } else {
      const noun = this.t(this.nounOf(api));
      const deviceId = first?.sourceId;
      if (id && deviceId) {
        text = this.t(gettext('Live test successful: created {{noun}} {{id}} on device {{deviceId}}{{externalId}}.'), { noun, id, deviceId, externalId });
      } else if (deviceId) {
        text = this.t(gettext('Live test successful: created {{noun}} on device {{deviceId}}{{externalId}}.'), { noun, deviceId, externalId });
      } else if (id) {
        text = this.t(gettext('Live test successful: created {{noun}} {{id}}.'), { noun, id });
      } else {
        text = this.t(gettext('Live test successful: created {{noun}}.'), { noun });
      }
    }

    const more = (result.requests?.length ?? 0) - 1;
    if (more > 0) {
      text += ' ' + (more === 1
        ? this.t(gettext('1 more request sent; see "Show Next Test Result".'))
        : this.t(gettext('{{count}} more requests sent; see "Show Next Test Result".'), { count: more }));
    }
    if (result.testDeviceId) {
      const testIdType = first?.externalIdType ? first.externalIdType + ' ' : '';
      const testDevice = result.testDeviceExternalId
        ? `${result.testDeviceId} (${testIdType}${result.testDeviceExternalId})`
        : result.testDeviceId;
      text += ' ' + (result.testDeviceCreated === false
        ? this.t(gettext('Reused test device {{testDevice}} from an earlier Live Test.'), { testDevice })
        : this.t(gettext('Test device {{testDevice}} created; remove it under Monitoring > Test device.'), { testDevice }));
    }
    return text;
  }

  /** The (translatable) noun for the kind of object a request creates. */
  private nounOf(api: string): string {
    switch (api) {
      case 'ALARM': return gettext('alarm');
      case 'EVENT': return gettext('event');
      case 'MEASUREMENT': return gettext('measurement');
      case 'OPERATION': return gettext('operation');
      default: return gettext('object');
    }
  }

  private tryParse(value: string): any {
    try {
      return JSON.parse(value);
    } catch {
      return undefined;
    }
  }

  private async handleError(message: string, error: unknown): Promise<void> {
    const errorMsg = this.extractErrorMessage(error);
    const translated = this.t(message);
    this.alertService.danger(errorMsg ? `${translated}: ${errorMsg}` : translated);
  }

  private showConfirmation(title: string, message: string, labels: { ok: string; cancel: string }): Promise<boolean> {
    const modalRef = this.bsModalService.show(ConfirmationModalComponent, { initialState: { title, message, labels } });
    return firstValueFrom(modalRef.content.closeSubject);
  }

  private extractErrorMessage(error: unknown): string {
    if (error instanceof Error) {
      return error.message;
    }
    return String(error || '');
  }

  private parseJsonContent(content: Content): any {
    if ('text' in content && content.text) {
      try {
        return JSON.parse(content.text);
      } catch {
        return null;
      }
    }
    return content['json'];
  }

  /** Error / warning counts for the console header, so they show even while it is collapsed. */
  get logCounts(): { errors: number; warnings: number } {
    const logs = this.testingModel?.logs ?? [];
    let errors = 0;
    let warnings = 0;
    for (const line of logs) {
      const level = this.getLogLevel(line);
      if (level === 'error') errors++;
      else if (level === 'warn') warnings++;
    }
    return { errors, warnings };
  }

  /**
   * Splits a raw console line into level, a short tag and the message. The backend prefixes lines
   * with their origin ("JS WARN: ", "WARNING: ", ...); showing that prefix on every line is noise
   * and the colour alone is no use to colour-blind users, so the level becomes a fixed-width tag.
   */
  toLogEntry(line: string): { level: string; tag: string; text: string } {
    const level = this.getLogLevel(line);
    const tags: Record<string, string> = { error: 'ERROR', warn: 'WARN', debug: 'DEBUG', info: 'INFO', log: 'LOG' };
    const text = line.replace(/^(JS ERROR|JS WARN|JS DEBUG|JS LOG|ERROR|WARNING|INFO):\s?/, '');
    return { level, tag: tags[level], text };
  }

  async copyConsole(): Promise<void> {
    try {
      await navigator.clipboard.writeText((this.testingModel.logs ?? []).join('\n'));
      this.alertService.info(gettext('Console output copied to clipboard.'));
    } catch {
      this.alertService.warning(gettext('Could not copy the console output. Select the text and copy it manually.'));
    }
  }

  getLogLevel(line: string): string {
    if (line.startsWith('ERROR:') || line.startsWith('JS ERROR:')) return 'error';
    if (line.startsWith('JS WARN:') || line.startsWith('WARNING:')) return 'warn';
    if (line.startsWith('JS DEBUG:')) return 'debug';
    if (line.startsWith('INFO')) return 'info';
    return 'log';
  }

  private extractTopicSample(contentAsJson: any): string {
    if (!contentAsJson?.[MappingTokens.TOPIC_LEVEL] || !Array.isArray(contentAsJson[MappingTokens.TOPIC_LEVEL])) {
      return '';
    }

    return contentAsJson[MappingTokens.TOPIC_LEVEL]
      .filter(item => item !== undefined && item !== null)
      .join('/');
  }
}