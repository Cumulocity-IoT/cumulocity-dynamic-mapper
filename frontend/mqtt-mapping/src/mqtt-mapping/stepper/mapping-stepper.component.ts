import { CdkStep } from '@angular/cdk/stepper';
import { AfterContentChecked, Component, ElementRef, EventEmitter, Input, OnInit, Output, ViewChild, ViewEncapsulation } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { AlertService, C8yStepper } from '@c8y/ngx-components';
import { JsonEditorComponent } from '@maaxgr/ang-jsoneditor';
import * as _ from 'lodash';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { Subject } from 'rxjs';
import { debounceTime } from "rxjs/operators";
import { API, Mapping, MappingSubstitution, QOS, SnoopStatus, ValidationError } from "../../shared/configuration.model";
import { checkPropertiesAreValid, checkSubstitutionIsValid, deriveTemplateTopicFromTopic, getSchema, isWildcardTopic, normalizeTopic, SAMPLE_TEMPLATES, SCHEMA_PAYLOAD, splitTopic, TOKEN_DEVICE_TOPIC } from "../../shared/helper";
import { OverwriteDeviceIdentifierModalComponent } from '../overwrite/overwrite-device-identifier-modal.component';
import { OverwriteSubstitutionModalComponent } from '../overwrite/overwrite-substitution-modal.component';
import { MappingService } from '../shared/mapping.service';

@Component({
  selector: 'mapping-stepper',
  templateUrl: 'mapping-stepper.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
})

export class MappingStepperComponent implements OnInit, AfterContentChecked {

  @Input() mapping: Mapping;
  @Input() mappings: Mapping[];
  @Input() editMode: boolean;
  @Output() onCancel = new EventEmitter<any>();
  @Output() onCommit = new EventEmitter<Mapping>();

  API = API;
  ValidationError = ValidationError;
  QOS = QOS;
  SnoopStatus = SnoopStatus;
  keys = Object.keys;
  values = Object.values;
  isWildcardTopic = isWildcardTopic;
  SAMPLE_TEMPLATES = SAMPLE_TEMPLATES;
  COLOR_HIGHLIGHTED: string = 'lightgrey'; //#5FAEEC';

  propertyForm: FormGroup;
  templateForm: FormGroup;
  testForm: FormGroup;
  templateSource: any;
  templateTarget: any;
  dataTesting: any;
  selectionList: any = [];

  editorOptionsSource: any
  editorOptionsTarget: any
  editorOptionsTesting: any
  sourceExpressionResult: string = '';
  sourceExpressionErrorMsg: string = '';
  markedDeviceIdentifier: string = '';
  showConfigMapping: boolean = false;
  selectedSubstitution: number = -1;
  snoopedTemplateCounter: number = 0;
  currentSubstitution: MappingSubstitution = new MappingSubstitution('', '', false);


  private setSelection = function (node: any, event: any) {
    if (event.type == "click") {
      // determine the json editor where the click happened
      let target = '';
      event.path.forEach(element => {
        if (element.localName == "json-editor") {
          target = element.parentElement.id;
        }
      });

      var path = "";
      node.path.forEach(n => {
        if (typeof n === 'number') {
          path = path.substring(0, path.length - 1);
          path += '[' + n + ']';
        } else {
          path += n;
        }
        path += ".";
      });
      path = path.replace(/\.$/g, '')

      for (let item of this.selectionList) {
        //console.log("Reset item:", item);
        item.setAttribute('style', null);
      }
      // test in which editor the click occured 
      if (target == "editorTargetRef") {
        this.setSelectionToPath(this.editorTarget, path)
        this.currentSubstitution.pathTarget = path;
        this.currentSubstitution.definesIdentifier = false;
      } else if (target == "editorSourceRef") {
        // test if double-clicked then select item and evaluate expression
        this.setSelectionToPath(this.editorSource, path)
        this.updateSourceExpressionResult(path);
        this.currentSubstitution.pathSource = path;
      }
    }
  }.bind(this)

  @ViewChild('editorSource', { static: false }) editorSource!: JsonEditorComponent;
  @ViewChild('editorTarget', { static: false }) editorTarget!: JsonEditorComponent;

  @ViewChild(C8yStepper, { static: false })
  stepper: C8yStepper;

  constructor(
    private bsModalService: BsModalService,
    public mappingService: MappingService,
    private elementRef: ElementRef,
  ) { }

  ngAfterContentChecked(): void {
    // if json source editor is displayed then choose the first selection
    const editorSourceRef = this.elementRef.nativeElement.querySelector('#editorSourceRef');
    if (editorSourceRef != null && !editorSourceRef.getAttribute("listener")) {
      //console.log("I'm here, ngAfterContentChecked", editorSourceRef, editorSourceRef.getAttribute("listener"));
      this.selectedSubstitution = 0;
      this.onSelectSubstitution(this.selectedSubstitution);
      editorSourceRef.setAttribute("listener", "true");
    }
  }

  ngOnInit() {
    console.log("Mapping to be updated:", this.mapping, this.editMode);
    //console.log ("ElementRef:", this.elementRef.nativeElement);
    this.initPropertyForm();
    this.initTemplateForm();
    this.editorOptionsSource = {
      modes: ['tree', 'code'],
      statusBar: false,
      navigationBar: false,
      enableSort: false,
      enableTransform: false,
      enableSearch: false,
      onEvent: this.setSelection,
      schema: SCHEMA_PAYLOAD
    };

    this.editorOptionsTarget = {
      modes: ['tree', 'code'],
      statusBar: false,
      navigationBar: false,
      enableSort: false,
      enableTransform: false,
      enableSearch: false,
      onEvent: this.setSelection,
      schema: getSchema(this.mapping.targetAPI)
    };

    this.editorOptionsTesting = {
      modes: ['form'],
      statusBar: false,
      navigationBar: false,
      enableSort: false,
      enableTransform: false,
      enableSearch: false,
      schema: SCHEMA_PAYLOAD
    };

    this.enrichTemplates();
    this.initMarkedDeviceIdentifier();
    //this.onTopicUpdated();
    this.onSourceExpressionUpdated();
  }

  private initPropertyForm(): void {
    this.propertyForm = new FormGroup({
      id: new FormControl(this.mapping.id, Validators.required),
      targetAPI: new FormControl(this.mapping.targetAPI, Validators.required),
      subscriptionTopic: new FormControl(this.mapping.subscriptionTopic, Validators.required),
      templateTopic: new FormControl(this.mapping.templateTopic),
      markedDeviceIdentifier: new FormControl(this.markedDeviceIdentifier),
      active: new FormControl(this.mapping.active),
      qos: new FormControl(this.mapping.qos, Validators.required),
      mapDeviceIdentifier: new FormControl(this.mapping.mapDeviceIdentifier),
      externalIdType: new FormControl(this.mapping.externalIdType),
      snoopTemplates: new FormControl(this.mapping.snoopTemplates),
    },
      checkPropertiesAreValid(this.mappings)
    );
  }

  private initTemplateForm(): void {
    this.templateForm = new FormGroup({
      cs: new FormGroup({
        ps: new FormControl(this.currentSubstitution.pathSource),
        pt: new FormControl(this.currentSubstitution.pathTarget),
        di: new FormControl(this.currentSubstitution.definesIdentifier),
      }),
      sourceExpressionResult: new FormControl(this.sourceExpressionResult),
    },
      checkSubstitutionIsValid(this.mapping)
    );
  }

  private setSelectionToPath(editor: JsonEditorComponent, path: string) {
    console.log("Set selection to path:", path);
    const ns = path.split(".");
    const selection = { path: ns };
    editor.setSelection(selection, selection)
  }

  private updateSourceExpressionResult(path: string) {
    // JSONPath library
    //this.sourceExpressionResult = JSON.stringify(JSONPath({path: path, json: this.editorSource.get()}), null, 4)
    // JMES library
    //this.sourceExpressionResult = JSON.stringify(search(this.editorSource.get() as any, path), null, 4)
    // JSONATA

    try {
      //console.log("Why this", path);
      this.sourceExpressionResult = this.mappingService.evaluateExpression(this.editorSource?.get(), path);
      this.sourceExpressionErrorMsg = '';
    } catch (error) {
      console.log("Error evaluating expression: ", error);
      this.sourceExpressionErrorMsg = error.message
    }
  }

  onTopicUpdated(): void {
    this.propertyForm.get('subscriptionTopic').valueChanges.pipe(debounceTime(500))
      // distinctUntilChanged()
      .subscribe(val => {
        let touched = this.propertyForm.get('subscriptionTopic').dirty;
        console.log(`Topic changed is ${val}.`, touched);
        if (touched) {
          this.mapping.templateTopic = val as string;
        }
      });
  }

  onTopicChanged(event): void {
    console.log("Starting normalization: ", this.mapping.subscriptionTopic);
    this.mapping.subscriptionTopic = normalizeTopic(this.mapping.subscriptionTopic);
    console.log("Ended normalization: ", this.mapping.subscriptionTopic);
    this.mapping.indexDeviceIdentifierInTemplateTopic = -1;
    this.initMarkedDeviceIdentifier();
    this.mapping.templateTopic = deriveTemplateTopicFromTopic(this.mapping.subscriptionTopic);
    // let containsWildcardTemplateTopic = isWildcardTopic(this.mapping.templateTopic);
    // this.propertyForm.get('markedDeviceIdentifier').setValidators(containsWildcardTemplateTopic ? Validators.required : Validators.nullValidator);
    // this.propertyForm.get('markedDeviceIdentifier').updateValueAndValidity();
  }

  onTemplateTopicChanged(event): void {
    this.mapping.templateTopic = normalizeTopic(this.mapping.templateTopic);
  }

  onSourceExpressionUpdated(): void {
    this.templateForm.get('cs').get('ps').valueChanges.pipe(debounceTime(500))
      // distinctUntilChanged()
      .subscribe(val => {
        //console.log(`Updated sourcePath ${val}.`, val);
        this.updateSourceExpressionResult(val);
      });
  }

  private getCurrentMapping(): Mapping {
    return {
      id: this.mapping.id,
      subscriptionTopic: normalizeTopic(this.mapping.subscriptionTopic),
      templateTopic: normalizeTopic(this.mapping.templateTopic),
      indexDeviceIdentifierInTemplateTopic: this.mapping.indexDeviceIdentifierInTemplateTopic,
      targetAPI: this.mapping.targetAPI,
      source: this.reduceTemplate(this.editorSource.get()),   //remove dummy field "_DEVICE_IDENT_", since it should not be stored
      target: this.reduceTemplate(this.editorTarget.get()),   //remove dummy field "_DEVICE_IDENT_", since it should not be stored
      active: this.mapping.active,
      tested: this.mapping.tested || false,
      qos: this.mapping.qos,
      substitutions: this.mapping.substitutions,
      mapDeviceIdentifier: this.mapping.mapDeviceIdentifier,
      externalIdType: this.mapping.externalIdType,
      snoopTemplates: this.mapping.snoopTemplates,
      snoopedTemplates: this.mapping.snoopedTemplates,
      lastUpdate: Date.now(),
    };
  }

  async onCommitButton() {
    this.onCommit.emit(this.getCurrentMapping());
  }

  async onTestTransformation() {
    this.dataTesting = await this.mappingService.testResult(this.getCurrentMapping(), false);
  }

  async onSendTest() {
    this.dataTesting = await this.mappingService.sendTestResult(this.getCurrentMapping());
    this.mapping.tested = (this.dataTesting != '' );
  }

  onSelectDeviceIdentifier() {
    let parts: string[] = splitTopic(this.mapping.templateTopic);
    if (this.mapping.indexDeviceIdentifierInTemplateTopic < parts.length - 1) {
      this.mapping.indexDeviceIdentifierInTemplateTopic++;
    } else {
      this.mapping.indexDeviceIdentifierInTemplateTopic = -1;
    }
    while (this.mapping.indexDeviceIdentifierInTemplateTopic < parts.length - 1 && parts[this.mapping.indexDeviceIdentifierInTemplateTopic] == "/") {
      if (this.mapping.indexDeviceIdentifierInTemplateTopic < parts.length - 1) {
        this.mapping.indexDeviceIdentifierInTemplateTopic++;
      } else {
        this.mapping.indexDeviceIdentifierInTemplateTopic = 0;
      }
    }
    this.markedDeviceIdentifier = parts[this.mapping.indexDeviceIdentifierInTemplateTopic];
  }

  private initMarkedDeviceIdentifier() {
    if (this.mapping?.templateTopic != undefined) {
      let parts: string[] = splitTopic(this.mapping.templateTopic);
      if (this.mapping.indexDeviceIdentifierInTemplateTopic < parts.length && this.mapping.indexDeviceIdentifierInTemplateTopic != -1) {
        this.markedDeviceIdentifier = parts[this.mapping.indexDeviceIdentifierInTemplateTopic];
      } else {
        this.markedDeviceIdentifier = '';
      }
    }
  }

  async onSampleButton() {
    this.templateTarget = JSON.parse(SAMPLE_TEMPLATES[this.mapping.targetAPI]);
    if (this.mapping.targetAPI == API.INVENTORY) {
      this.templateTarget = this.expandTemplate (this.templateTarget);
    }
  }

  async onCancelButton() {
    this.onCancel.emit();
  }

  public onNextSelected(event: { stepper: C8yStepper; step: CdkStep }): void {

    console.log("OnNextSelected", event.step.label, this.mapping)

    if (event.step.label == "Define topic") {
      console.log("Populate jsonPath if wildcard:", isWildcardTopic(this.mapping.subscriptionTopic), this.mapping.substitutions.length)
      console.log("Templates from mapping:", this.mapping.target, this.mapping.source)
      if (this.propertyForm.get('subscriptionTopic').touched) {
        this.mapping.substitutions = [];
      }
      this.updateSubstitutions();
      this.enrichTemplates();
      this.editorTarget.setSchema(getSchema(this.mapping.targetAPI), null);
    } else if (event.step.label == "Define templates") {
      console.log("Templates source from editor:", this.templateSource, this.editorSource.getText(), this.getCurrentMapping())
      this.dataTesting = this.editorSource.get();
    }

    if (this.mapping.snoopTemplates == SnoopStatus.ENABLED && this.mapping.snoopedTemplates.length == 0) {
      console.log("Ready to snoop ...");
      this.onCommit.emit(this.getCurrentMapping());
    } else {
      event.stepper.next();
    }

  }

  private enrichTemplates() {
    this.templateSource = JSON.parse(this.mapping.source);
    //add dummy field TOKEN_DEVICE_TOPIC to use for mapping the device identifier form the topic ending
    if (isWildcardTopic(this.mapping.subscriptionTopic)) {
      this.templateSource = this.expandTemplate(this.templateSource);
    }
    this.templateTarget = JSON.parse(this.mapping.target);
    if (!this.editMode) {
      this.templateTarget = JSON.parse(SAMPLE_TEMPLATES[this.mapping.targetAPI]);
      console.log("Sample template", this.templateTarget, getSchema(this.mapping.targetAPI));
    }
    if (this.mapping.targetAPI == API.INVENTORY) {
      this.templateTarget = this.expandTemplate(this.templateTarget);
    }
  }

  async onSnoopedSourceTemplates() {
    if (this.snoopedTemplateCounter >= this.mapping.snoopedTemplates.length) {
      this.snoopedTemplateCounter = 0;
    }
    this.templateSource = JSON.parse(this.mapping.snoopedTemplates[this.snoopedTemplateCounter]);
    //add dummy field "_DEVICE_IDENT_" to use for mapping the device identifier form the topic ending
    if (isWildcardTopic(this.mapping.subscriptionTopic)) {
      this.templateSource = this.expandTemplate(this.templateSource);
    }
    // disable further snooping for this template
    this.mapping.snoopTemplates = SnoopStatus.STOPPED;
    this.snoopedTemplateCounter++;
  }

  public onAddSubstitution() {
    if (this.currentSubstitution.isValid()) {
      this.addSubstitution(this.currentSubstitution);
      this.selectedSubstitution = -1;
      console.log("New substitution", this.currentSubstitution, this.mapping.substitutions);
      this.currentSubstitution.reset();
      this.templateForm.updateValueAndValidity({ 'emitEvent': true });
    }
  }

  public onDeleteSubstitutions() {
    this.mapping.substitutions = [];
    this.updateSubstitutions();
    console.log("Cleared substitutions!");
  }

  public onDeleteSubstitution() {
    console.log("Delete marked substitution", this.selectedSubstitution);
    if (this.selectedSubstitution < this.mapping.substitutions.length) {
      this.mapping.substitutions.splice(this.selectedSubstitution, 1);
      this.selectedSubstitution = -1;
    }
    this.updateSubstitutions();
  }

  private updateSubstitutions() {
    if (this.mapping.substitutions.length == 0 && (isWildcardTopic(this.mapping.subscriptionTopic) || this.mapping.indexDeviceIdentifierInTemplateTopic != -1)) {
      if (this.mapping.targetAPI != API.INVENTORY) {
        this.mapping.substitutions.push(new MappingSubstitution(TOKEN_DEVICE_TOPIC, "source.id", true));
      } else {
        // if creating new device then the json body contains only a dummy field
        this.mapping.substitutions.push(new MappingSubstitution(TOKEN_DEVICE_TOPIC, TOKEN_DEVICE_TOPIC, true));
      }
    }
  }

  private addSubstitution(st: MappingSubstitution) {
    let sub: MappingSubstitution = _.clone(st);
    if (sub.pathTarget == "source.id") {
      sub.definesIdentifier = true;
    }
    let updatePending = new Subject<boolean>();
    // test 2
    // only one susbsitution can define the deviceIdentifier, thus set the others to false
    let suby = updatePending.subscribe(update => {
      if (update) {
        let substitutionOld: MappingSubstitution[] = [];
        this.mapping.substitutions.forEach(s => {
          if (sub.definesIdentifier && s.definesIdentifier) {
            substitutionOld.push(s)
          }
        })

        if (substitutionOld.length == 1) {
          const initialState = {
            substitutionOld: substitutionOld[0],
            substitutionNew: sub
          }
          const overwriteModalRef: BsModalRef = this.bsModalService.show(OverwriteDeviceIdentifierModalComponent, { initialState });
          overwriteModalRef.content.closeSubject.subscribe(
            (overwrite: boolean) => {
              console.log("Overwriting definesIdentifier I:", overwrite, substitutionOld[0], sub);
              if (overwrite) {
                substitutionOld[0].definesIdentifier = false;
              } else {
                sub.definesIdentifier = false;
              }
              this.templateForm.updateValueAndValidity({ 'emitEvent': true });
              console.log("Overwriting definesIdentifier II:", overwrite, substitutionOld[0], sub);
            }
          )
          this.mapping.substitutions.push(sub);
        } else if (substitutionOld.length == 0) {
          this.mapping.substitutions.push(sub);
        } else {
          console.error("Someting is wrong, since more than one substitution is marked to define the device identifier:", substitutionOld);
        }
      }

    });

    // test 1
    // test if mapping for sub.pathTarget already exists. Then ignore the new substitution. 
    // User has to remove the old substitution.
    let existingSubstitution = -1;
    this.mapping.substitutions.forEach((s, index) => {
      if (sub.pathTarget == s.pathTarget) {
        existingSubstitution = index;
      }
    })

    if (existingSubstitution != -1) {
      const initialState = {
        substitution: this.mapping.substitutions[existingSubstitution]
      }
      const overwriteModalRef: BsModalRef = this.bsModalService.show(OverwriteSubstitutionModalComponent, { initialState });
      overwriteModalRef.content.closeSubject.subscribe(
        (overwrite: boolean) => {
          console.log("Overwriting substitution I:", overwrite, this.mapping.substitutions);
          if (overwrite) {
            // when overwritting substitution then copy deviceIdentifier property
            sub.definesIdentifier = this.mapping.substitutions[existingSubstitution].definesIdentifier;
            this.mapping.substitutions[existingSubstitution] = sub;
          }
          updatePending.next(false);
          this.templateForm.updateValueAndValidity({ 'emitEvent': true });
          console.log("Overwriting substitution II:", overwrite, this.mapping.substitutions);
        }
      );
    } else {
      updatePending.next(true);
    }
    suby.unsubscribe();
  }

  public onSelectNextSubstitution() {
    // changing of colors is currently diabled, to enable these uncomment the following stmt.
    if (this.selectedSubstitution >= this.mapping.substitutions.length - 1) {
      this.selectedSubstitution = -1;
    }
    this.selectedSubstitution++;
    this.onSelectSubstitution(this.selectedSubstitution);
  }

  public onSelectSubstitution(selected: number) {
    // reset background color of old selection list
    for (let item of this.selectionList) {
      item.setAttribute('style', null);
    }
    this.currentSubstitution = new MappingSubstitution(this.mapping.substitutions[selected].pathSource, this.mapping.substitutions[selected].pathTarget, this.mapping.substitutions[selected].definesIdentifier);
    this.updateSourceExpressionResult(this.currentSubstitution.pathSource);
    this.setSelectionToPath(this.editorSource, this.currentSubstitution.pathSource);
    this.setSelectionToPath(this.editorTarget, this.currentSubstitution.pathTarget);
    this.selectionList = this.elementRef.nativeElement.querySelectorAll('.jsoneditor-selected');
    for (let item of this.selectionList) {
      item.setAttribute('style', `background: ${this.COLOR_HIGHLIGHTED};`);
    }
  }

  private expandTemplate(t: object): object {
    return {
      ...t,
      _DEVICE_IDENT_: "909090"
    };
  }

  private reduceTemplate(t: object): string {
    delete t[TOKEN_DEVICE_TOPIC];
    let tt = JSON.stringify(t);
    return tt;
  }
}