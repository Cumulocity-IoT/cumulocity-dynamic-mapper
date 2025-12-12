// mapping-substitution-step.component.ts
import {
  Component,
  EventEmitter,
  Input,
  Output,
  ViewChild,
  OnInit,
  ElementRef,
  inject
} from '@angular/core';
import { FormGroup } from '@angular/forms';
import { AlertService, BottomDrawerService, CoreModule } from '@c8y/ngx-components';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { debounceTime, distinctUntilChanged, Observable } from 'rxjs';
import {
  COLOR_HIGHLIGHTED,
  JsonEditorComponent,
  Mapping,
  StepperConfiguration,
  Feature,
  isSubstitutionsAsCode,
  RepairStrategy
} from '../../shared';
import { EditorMode } from '../shared/stepper.model';
import { SubstitutionRendererComponent } from '../substitution/substitution-grid.component';
import { AIPromptComponent } from '../prompt/ai-prompt.component';
import { AgentObjectDefinition, AgentTextDefinition } from '../shared/ai-prompt.model';
import { MappingStepperService } from '../service/mapping-stepper.service';
import { SubstitutionManagementService } from '../service/substitution-management.service';
import { isExpression } from '../shared/util';
import { CommonModule } from '@angular/common';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { CollapseModule } from 'ngx-bootstrap/collapse';

@Component({
  selector: 'd11r-mapping-substitution-step',
  templateUrl: './mapping-substitution-step.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  standalone: true,
  imports: [CoreModule, CommonModule, PopoverModule, CollapseModule, SubstitutionRendererComponent, JsonEditorComponent]
})
export class MappingSubstitutionStepComponent implements OnInit {
  @Input() mapping: Mapping;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() sourceTemplate: any;
  @Input() targetTemplate: any;
  @Input() sourceSystem: string;
  @Input() targetSystem: string;
  @Input() feature: Feature;
  @Input() aiAgentDeployed: boolean;
  @Input() aiAgent: AgentObjectDefinition | AgentTextDefinition | null;
  @Input() updateSourceEditor: EventEmitter<any>;
  @Input() updateTargetEditor: EventEmitter<any>;
  @Input() mappingCode: string;
  @Input() codeEditorLabel: string;
  @Input() codeEditorHelp: string;
  @Input() currentStepIndex: number;

  @Output() mappingCodeChange = new EventEmitter<string>();
  @Output() codeTemplateSelected = new EventEmitter<void>();
  @Output() createCodeTemplate = new EventEmitter<void>();
  @Output() updateSubstitutionValidity = new EventEmitter<void>();

  @ViewChild('editorSourceStepSubstitution', { static: false })
  editorSourceStepSubstitution!: JsonEditorComponent;

  @ViewChild('editorTargetStepSubstitution', { static: false })
  editorTargetStepSubstitution!: JsonEditorComponent;

  @ViewChild(SubstitutionRendererComponent, { static: false })
  substitutionChild!: SubstitutionRendererComponent;

  @ViewChild('substitutionModelSourceExpression')
  substitutionModelSourceExpression: ElementRef<HTMLTextAreaElement>;

  @ViewChild('substitutionModelTargetExpression')
  substitutionModelTargetExpression: ElementRef<HTMLTextAreaElement>;

  private alertService = inject(AlertService);
  private bottomDrawerService = inject(BottomDrawerService);
  private stepperService = inject(MappingStepperService);
  private substitutionService = inject(SubstitutionManagementService);

  readonly COLOR_HIGHLIGHTED = COLOR_HIGHLIGHTED;
  readonly EditorMode = EditorMode;

  templateForm: FormGroup = new FormGroup({});
  substitutionFormly: FormGroup = new FormGroup({});
  substitutionFormlyFields: FormlyFieldConfig[];
  substitutionModel: any = {};
  selectedSubstitution: number = -1;
  expertMode: boolean = false;
  targetTemplateHelp = 'The template contains the dummy field <code>_TOPIC_LEVEL_</code>...';

  isSubstitutionValid$: Observable<boolean>;
  sourceCustomMessage$: Observable<string>;
  targetCustomMessage$: Observable<string>;

  editorOptionsSourceSubstitution = {
    mode: 'tree' as const,
    removeModes: ['text', 'table'],
    mainMenuBar: true,
    navigationBar: false,
    statusBar: false,
    readOnly: true,
    name: 'message'
  };

  editorOptionsTargetSubstitution = {
    mode: 'tree' as const,
    removeModes: ['text', 'table'],
    mainMenuBar: true,
    navigationBar: false,
    readOnly: true,
    statusBar: true
  };

  ngOnInit(): void {
    this.isSubstitutionValid$ = this.stepperService.isSubstitutionValid$;
    this.sourceCustomMessage$ = this.stepperService.sourceCustomMessage$;
    this.targetCustomMessage$ = this.stepperService.targetCustomMessage$;

    this.initializeSubstitutionModel();
    this.initializeFormlyFields();
    this.updateEditorPermissions();
  }

  private initializeSubstitutionModel(): void {
    this.substitutionModel = {
      stepperConfiguration: this.stepperConfiguration,
      pathSource: '',
      pathTarget: '',
      pathSourceIsExpression: false,
      pathTargetIsExpression: false,
      repairStrategy: RepairStrategy.DEFAULT,
      expandArray: false,
      targetExpression: { result: '', resultType: 'empty', valid: false },
      sourceExpression: { result: '', resultType: 'empty', valid: false }
    };
  }

  private initializeFormlyFields(): void {
    this.substitutionFormlyFields = [
      {
        fieldGroup: [
          {
            className: 'col-lg-5 col-lg-offset-1',
            key: 'pathSource',
            type: 'd11r-input',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Source Expression',
              class: 'input-sm',
              disabled: this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
                !this.stepperConfiguration.allowDefiningSubstitutions,
              placeholder: '$join([$substring(txt,5), id]) or $number(id)/10',
              description: `Use <a href="https://jsonata.org" target="_blank">JSONata</a>
              in your expressions:
              <ol>
                <li>to convert a UNIX timestamp to ISO date format use:
                  <code>$fromMillis($number(deviceTimestamp))</code>
                </li>
                <li>to join substring starting at position 5 of property <code>txt</code> with
                  device
                  identifier use: <code>$join([$substring(txt,5), "-", id])</code></li>
                <li>function chaining using <code>~</code> is supported. The expression <code>Account.Product.(Price * Quantity) ~> $sum()</code>
                  becomes <code>$sum(Account.Product.(Price * Quantity))</code></li>
              </ol>`,
              required: true,
              customMessage: this.sourceCustomMessage$
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                field.formControl.valueChanges.pipe(
                  debounceTime(500),
                  distinctUntilChanged()
                ).subscribe(path => this.updateSourceExpressionResult(path));
              }
            }
          },
          {
            className: 'col-lg-5',
            key: 'pathTarget',
            type: 'd11r-input',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Target Expression',
              disabled: this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
                !this.stepperConfiguration.allowDefiningSubstitutions,
              description: `Use <a href="https://jsonata.org" target="_blank">JSONata</a>
              in your expressions:
              <ol>
                <li>to convert a UNIX timestamp to ISO date format use:
                  <code>$fromMillis($number(deviceTimestamp))</code>
                </li>
                <li>to join substring starting at position 5 of property <code>txt</code> with
                  device
                  identifier use: <code>$join([$substring(txt,5), "-", id])</code></li>
                <li>function chaining using <code>~</code> is supported. The expression <code>Account.Product.(Price * Quantity) ~> $sum()</code>
                  becomes <code>$sum(Account.Product.(Price * Quantity))</code></li>
              </ol>`,
              required: true,
              customMessage: this.targetCustomMessage$
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                field.formControl.valueChanges.pipe(
                  debounceTime(500),
                  distinctUntilChanged()
                ).subscribe(path => this.updateTargetExpressionResult(path));
              }
            }
          }
        ]
      }
    ];
  }

  private updateEditorPermissions(): void {
    if (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole) {
      this.editorOptionsSourceSubstitution.readOnly = true;
      this.editorOptionsTargetSubstitution.readOnly = true;
    }
  }

  async updateSourceExpressionResult(path: string): Promise<void> {
    try {
      const result = await this.stepperService.evaluateSourceExpression(
        this.editorSourceStepSubstitution?.get(),
        path
      );
      this.substitutionModel.sourceExpression = result;
      this.substitutionModel.pathSourceIsExpression = isExpression(this.substitutionModel.pathSource);
      this.substitutionFormly.get('pathSource').setErrors(null);

      if (result.resultType == 'Array' && !this.substitutionModel.expandArray) {
        this.alertService.info('Current expression extracts an array. Consider using "Expand as array"...');
      }
    } catch (error) {
      this.substitutionModel.sourceExpression.valid = false;
      this.substitutionFormly.get('pathSource').setErrors({
        validationError: { message: error.message }
      });
    }

    this.substitutionModel = { ...this.substitutionModel };
    setTimeout(() => this.manualResize('source'), 0);
  }

  async updateTargetExpressionResult(path: string): Promise<void> {
    try {
      const result = await this.stepperService.evaluateTargetExpression(
        this.editorTargetStepSubstitution?.get(),
        path
      );
      this.substitutionModel.targetExpression = result;
      this.substitutionFormly.get('pathTarget').setErrors(null);

      if (path == '$') {
        this.stepperService.targetCustomMessage$.next(
          'By specifying "$" you selected the root of the target template...'
        );
      }
    } catch (error) {
      this.substitutionModel.targetExpression.valid = false;
      this.substitutionFormly.get('pathTarget').setErrors({
        validationError: { message: error.message }
      });
    }

    this.substitutionModel = { ...this.substitutionModel };
    setTimeout(() => this.manualResize('target'), 0);
  }

  async onSelectedPathSourceChanged(path: string): Promise<void> {
    this.substitutionFormly.get('pathSource').setValue(path);
    this.substitutionModel.pathSource = path;
    this.substitutionModel.pathSourceIsExpression = isExpression(path);
  }

  async onSelectedPathTargetChanged(path: string): Promise<void> {
    this.substitutionFormly.get('pathTarget').setValue(path);
    this.substitutionModel.pathTarget = path;
  }

  onAddSubstitution(): void {
    if (!this.isSubstitutionValid()) {
      this.alertService.warning('Please select nodes in both templates to define a substitution.');
      return;
    }

    this.substitutionModel.expandArray = false;
    this.substitutionModel.repairStrategy = RepairStrategy.DEFAULT;

    this.substitutionService.addSubstitution(
      this.substitutionModel,
      this.mapping,
      this.stepperConfiguration,
      this.expertMode,
      () => this.updateSubstitutionValidity.emit()
    );

    this.selectedSubstitution = -1;
  }

  onUpdateSubstitution(): void {
    this.substitutionService.updateSubstitution(
      this.selectedSubstitution,
      this.substitutionModel,
      this.mapping,
      this.stepperConfiguration,
      () => this.updateSubstitutionValidity.emit()
    );
  }

  onDeleteSubstitution(selected: number): void {
    this.substitutionService.deleteSubstitution(
      selected,
      this.mapping,
      () => this.updateSubstitutionValidity.emit()
    );
  }

  async onSelectSubstitution(selected: number): Promise<void> {
    if (selected < 0 || selected >= this.mapping.substitutions.length) return;

    this.selectedSubstitution = selected;
    this.substitutionModel = {
      ...this.mapping.substitutions[selected],
      stepperConfiguration: this.stepperConfiguration
    };
    this.substitutionModel.pathSourceIsExpression = isExpression(this.substitutionModel.pathSource);

    await Promise.all([
      this.editorSourceStepSubstitution.setSelectionToPath(this.substitutionModel.pathSource),
      this.editorTargetStepSubstitution.setSelectionToPath(this.substitutionModel.pathTarget)
    ]);
  }

  toggleExpertMode(): void {
    this.expertMode = !this.expertMode;
  }

  async openGenerateSubstitutionDrawer(): Promise<void> {
    const testMapping = { ...this.mapping };
    testMapping.sourceTemplate = JSON.stringify(this.sourceTemplate);
    testMapping.targetTemplate = JSON.stringify(this.targetTemplate);

    const drawer = this.bottomDrawerService.openDrawer(AIPromptComponent, {
      initialState: { mapping: testMapping, aiAgent: this.aiAgent }
    });

    try {
      const resultOf = await drawer.instance.result;

      if (isSubstitutionsAsCode(this.mapping)) {
        if (typeof resultOf === 'string' && resultOf.trim()) {
          this.mappingCodeChange.emit(resultOf);
          this.alertService.success('Generated JavaScript code successfully.');
        } else {
          this.alertService.warning('No valid JavaScript code was generated.');
        }
      } else {
        if (Array.isArray(resultOf) && resultOf.length > 0) {
          this.alertService.success(`Generated ${resultOf.length} substitutions.`);
          this.mapping.substitutions.splice(0);
          resultOf.forEach(sub => {
            this.substitutionService.addSubstitution(
              sub,
              this.mapping,
              this.stepperConfiguration,
              this.expertMode,
              () => this.updateSubstitutionValidity.emit()
            );
          });
        } else {
          this.alertService.warning('No substitutions were generated.');
        }
      }
    } catch (ex) {
      // User canceled
    }
  }

  onValueCodeChange(value: string): void {
    this.mappingCodeChange.emit(value);
  }

  addSubstitutionDisabled(): boolean {
    return !this.stepperConfiguration.showEditorSource ||
      this.stepperConfiguration.editorMode === EditorMode.READ_ONLY ||
      !this.isSubstitutionValid();
  }

  updateSubstitutionDisabled(): boolean {
    return !this.stepperConfiguration.showEditorSource ||
      this.stepperConfiguration.editorMode === EditorMode.READ_ONLY ||
      this.selectedSubstitution === -1 ||
      !this.isSubstitutionValid();
  }

  private isSubstitutionValid(): boolean {
    return this.substitutionService.isSubstitutionValid(this.substitutionModel);
  }

  private manualResize(source: 'source' | 'target'): void {
    const element = source === 'source'
      ? this.substitutionModelSourceExpression?.nativeElement
      : this.substitutionModelTargetExpression?.nativeElement;

    if (element) {
      element.style.height = '32px';
      element.style.height = element.scrollHeight + 'px';
    }
  }
}