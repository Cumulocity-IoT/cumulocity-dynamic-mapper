import { CdkStep } from '@angular/cdk/stepper';
import { Component, ElementRef, EventEmitter, Input, OnInit, Output, ViewChild, ViewEncapsulation } from '@angular/core';
import { FormBuilder, FormControl, FormGroup, Validators } from '@angular/forms';
import { AlertService, C8yStepper } from '@c8y/ngx-components';
import { JsonEditorComponent } from '@maaxgr/ang-jsoneditor';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { debounceTime } from "rxjs/operators";
import { API, Mapping, MappingSubstitution, QOS, SnoopStatus, ValidationError } from "../../shared/mqtt-configuration.model";
import { checkPropertiesAreValid, checkSubstituionIsValid, deriveTemplateTopicFromTopic, getSchema, isWildcardTopic, normalizeTopic, SAMPLE_TEMPLATES, SCHEMA_PAYLOAD, TOKEN_DEVICE_TOPIC } from "../../shared/mqtt-helper";
import { OverwriteSubstitutionModalComponent } from '../overwrite/overwrite-substitution-modal.component';
import { MQTTMappingService } from '../shared/mqtt-mapping.service';


@Component({
  selector: 'mapping-stepper',
  templateUrl: 'mqtt-mapping-stepper.component.html',
  styleUrls: ['../shared/mqtt-mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
})

export class MQTTMappingStepperComponent implements OnInit {

  @Input() mapping: Mapping;
  @Input() mappings: Mapping[];
  @Input() editMode: boolean;
  @Output() onCancel = new EventEmitter<any>();
  @Output() onCommit = new EventEmitter<Mapping>();

  COLOR_PALETTE = ['#d5f4e6', '#80ced6', '#fefbd8', '#618685', '#ffef96', '#50394c', '#b2b2b2', '#f4e1d2']
  API = API;
  ValidationError = ValidationError;
  QOS = QOS;
  SnoopStatus = SnoopStatus;
  keys = Object.keys;
  values = Object.values;
  SAMPLE_TEMPLATES = SAMPLE_TEMPLATES;

  paletteCounter: number = 0;
  nextColor: string;
  snoopedTemplateCounter: number = 0;
  isSubstitutionValid: boolean;
  containsWildcardTopic: boolean = false;
  containsWildcardTemplateTopic: boolean = false;
  substitutions: string = '';

  pathSource: string = '';
  pathTarget: string = '';
  templateSource: any;
  templateTarget: any;
  dataTesting: any;
  selectionList: any = [];
  definesIdentifier: boolean = false;

  clicksTarget: []
  clicksSource: []
  editorOptionsSource: any
  editorOptionsTarget: any
  editorOptionsTesting: any
  sourceExpressionResult: string = '';
  sourceExpressionErrorMsg: string = '';
  markedDeviceIdentifier: string = '';

  private setSelectionSource = function (node: any, event: any) {
    if (event.type == "click") {
      if (this.clicksSource == undefined) this.clicksSource = [];
      this.clicksSource.push(Date.now());
      this.clicksSource = this.clicksSource.slice(-2);
      let doubleClick = (this.clicksSource.length > 1 ? this.clicksSource[1] - this.clicksSource[0] : Infinity);
      //console.log("Set target editor event:", event.type, this.clicksTarget, doubleClick);
      var path = "";
      for (let i = 0; i < node.path.length; i++) {
        if (typeof node.path[i] === 'number') {
          path = path.substring(0, path.length - 1);
          path += '[' + node.path[i] + ']';

        } else {
          path += node.path[i];
        }
        if (i !== node.path.length - 1) path += ".";
      }
      for (let item of this.selectionList) {
        //console.log("Reset item:", item);
        item.setAttribute('style', null);
      }
      // test if doubleclicked, last two click occured within 750 ms
      if (doubleClick < 750) {
        this.setSelectionToPath(this.editorSource, path)
        this.definesIdentifier = false;
        this.updateSourceExpressionResult(path);
        this.pathSource = path;
      }
      //console.log("Set pathSource:", path);
    }
  }.bind(this)

  private setSelectionTarget = function (node: any, event: any) {
    if (event.type == "click") {
      if (this.clicksTarget == undefined) this.clicksTarget = [];
      this.clicksTarget.push(Date.now());
      this.clicksTarget = this.clicksTarget.slice(-2);
      let doubleClick = (this.clicksTarget.length > 1 ? this.clicksTarget[1] - this.clicksTarget[0] : Infinity);
      //console.log("Set target editor event:", event.type, this.clicksTarget, doubleClick);

      var path = "";
      for (let i = 0; i < node.path.length; i++) {
        if (typeof node.path[i] === 'number') {
          path = path.substring(0, path.length - 1);
          path += '[' + node.path[i] + ']';

        } else {
          path += node.path[i];
        }
        if (i !== node.path.length - 1) path += ".";
      }
      for (let item of this.selectionList) {
        //console.log("Reset item:", item);
        item.setAttribute('style', null);
      }
      // test if double-clicked
      if (doubleClick < 750) {
        this.setSelectionToPath(this.editorTarget, path)
        this.pathTarget = path;
      }
      //console.log("Set pathTarget:", path);
    }
  }.bind(this)

  @ViewChild('editorSource', { static: false }) editorSource!: JsonEditorComponent;
  @ViewChild('editorTarget', { static: false }) editorTarget!: JsonEditorComponent;

  @ViewChild(C8yStepper, { static: false })
  stepper: C8yStepper;

  showConfigMapping: boolean = false;

  isConnectionToMQTTEstablished: boolean;

  selectedSubstitution: number = -1;

  propertyForm: FormGroup;
  templateForm: FormGroup;
  testForm: FormGroup;
  sourcePathMissing: boolean = false;
  targetPathMissing: boolean = false;

  constructor(
    private bsModalService: BsModalService,
    public mqttMappingService: MQTTMappingService,
    public alertService: AlertService,
    private elementRef: ElementRef,
    private fb: FormBuilder,
  ) { }

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
      onEvent: this.setSelectionSource,
      schema: SCHEMA_PAYLOAD
    };

    this.editorOptionsTarget = {
      modes: ['tree', 'code'],
      statusBar: false,
      navigationBar: false,
      enableSort: false,
      enableTransform: false,
      enableSearch: false,
      onEvent: this.setSelectionTarget,
      schema: getSchema(this.mapping.targetAPI)
    };

    this.editorOptionsTesting = {
      modes: ['form'],
      statusBar: false,
      navigationBar: false,
      enableSort: false,
      enableTransform: false,
      enableSearch: false,
      onEvent: this.setSelectionSource,
      schema: SCHEMA_PAYLOAD
    };

    this.enrichTemplates();
    this.initMarkedDeviceIdentifier();
    //this.onTopicUpdated();
    this.onSourceExpressionUpdated();
    this.containsWildcardTopic = isWildcardTopic(this.mapping.topic);
    this.containsWildcardTemplateTopic = isWildcardTopic(this.mapping.templateTopic);
  }

  private initPropertyForm(): void {
    this.propertyForm = new FormGroup({
      id: new FormControl(this.mapping.id, Validators.required),
      targetAPI: new FormControl(this.mapping.targetAPI, Validators.required),
      topic: new FormControl(this.mapping.topic, Validators.required),
      markerWildcardTopic: new FormControl(this.containsWildcardTopic),
      markerWildcardTemplateTopic: new FormControl(this.containsWildcardTemplateTopic),
      templateTopic: new FormControl(this.mapping.templateTopic),
      markedDeviceIdentifier: new FormControl(this.markedDeviceIdentifier, ( this.containsWildcardTemplateTopic ? Validators.required : Validators.nullValidator) ),
      active: new FormControl(this.mapping.active),
      createNoExistingDevice: new FormControl(this.mapping.createNoExistingDevice, Validators.required),
      qos: new FormControl(this.mapping.qos, Validators.required),
      mapDeviceIdentifier: new FormControl(this.mapping.mapDeviceIdentifier),
      externalIdType: new FormControl(this.mapping.externalIdType),
      snoopTemplates: new FormControl(this.mapping.snoopTemplates),
    } , checkPropertiesAreValid(this.mappings) 
    );
  }

  private initTemplateForm(): void {
    this.templateForm = new FormGroup({
      pathSource: new FormControl(this.pathSource),
      pathTarget: new FormControl(this.pathTarget),
      definesIdentifier: new FormControl(this.definesIdentifier),
      sourceExpressionResult: new FormControl(this.sourceExpressionResult),
    },
     checkSubstituionIsValid(this.mapping));
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
      this.sourceExpressionResult = this.mqttMappingService.evaluateExpression(this.editorSource?.get(), path);
      this.sourceExpressionErrorMsg = '';
    } catch (error) {
      console.log("Error evaluating expression: ", error);
      this.sourceExpressionErrorMsg = error.message
    }
  }

  onTopicUpdated(): void {
    this.propertyForm.get('topic').valueChanges.pipe(debounceTime(500))
      // distinctUntilChanged()
      .subscribe(val => {
        let touched = this.propertyForm.get('topic').dirty;
        console.log(`Topic changed is ${val}.`, touched);
        if (touched) {
          this.mapping.templateTopic = val as string;
        }
      });
  }

  onTopicChanged(event): void {
    console.log("Starting normalization: ", this.mapping.topic);
    this.mapping.topic = normalizeTopic(this.mapping.topic);
    this.containsWildcardTopic = isWildcardTopic(this.mapping.topic);
    console.log("Ended normalization: ", this.mapping.topic);
    this.mapping.indexDeviceIdentifierInTemplateTopic = -1;
    this.initMarkedDeviceIdentifier();
    this.mapping.templateTopic = deriveTemplateTopicFromTopic(this.mapping.topic);
    this.containsWildcardTemplateTopic = isWildcardTopic(this.mapping.templateTopic);
    this.propertyForm.get('markedDeviceIdentifier').setValidators( this.containsWildcardTemplateTopic? Validators.required : Validators.nullValidator);
    this.propertyForm.get('markedDeviceIdentifier').updateValueAndValidity();
  }

  onTemplateTopicChanged(event): void {
    this.mapping.templateTopic = normalizeTopic(this.mapping.templateTopic);
  }

  onSourceExpressionUpdated(): void {
    this.templateForm.get('pathSource').valueChanges.pipe(debounceTime(500))
      // distinctUntilChanged()
      .subscribe(val => {
        //console.log(`Updated sourcePath ${val}.`, val);
        this.updateSourceExpressionResult(val);
      });
  }

  private getCurrentMapping(): Mapping {
    //remove dummy field "_DEVICE_IDENT_", since it should not be stored
    //if (!this.containsWildcardTopic) {
      let dts = this.editorSource.get()
      delete dts[TOKEN_DEVICE_TOPIC];
      let st = JSON.stringify(dts);
    //}

    let dtt = this.editorTarget.get()
    delete dtt[TOKEN_DEVICE_TOPIC];
    let tt = JSON.stringify(dtt);

    return {
      id: this.mapping.id,
      topic: normalizeTopic(this.mapping.topic),
      templateTopic: normalizeTopic(this.mapping.templateTopic),
      indexDeviceIdentifierInTemplateTopic: this.mapping.indexDeviceIdentifierInTemplateTopic,
      targetAPI: this.mapping.targetAPI,
      source: st,
      target: tt,
      active: this.mapping.active,
      tested: this.mapping.tested || false,
      createNoExistingDevice: this.mapping.createNoExistingDevice || false,
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
    let dataTesting = await this.mqttMappingService.testResult(this.getCurrentMapping(), false);
    this.dataTesting = dataTesting;
  }

  async onSendTest() {
    let { data, res } = await this.mqttMappingService.sendTestResult(this.getCurrentMapping());
    //console.log ("My data:", data );
    if (res.status == 200 || res.status == 201) {
      this.alertService.success("Successfully tested mapping!");
      this.mapping.tested = true;
      this.dataTesting = data as any;
    } else {
      let error = await res.text();
      this.alertService.danger("Failed to tested mapping: " + error);
    }
  }

  onSelectDeviceIdentifier() {
    let parts: string[] = this.mapping.templateTopic.split("/");
    if (this.mapping.indexDeviceIdentifierInTemplateTopic < parts.length - 1) {
      this.mapping.indexDeviceIdentifierInTemplateTopic++;
    } else {
      this.mapping.indexDeviceIdentifierInTemplateTopic = 0;
    }
    this.markedDeviceIdentifier = parts[this.mapping.indexDeviceIdentifierInTemplateTopic];
  }

  private initMarkedDeviceIdentifier() {
    if (this.mapping?.templateTopic != undefined) {
      let parts: string[] = this.mapping.templateTopic.split("/");
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
      this.templateTarget = {
        ...this.templateTarget,
        _DEVICE_IDENT_: "909090"
      };
    }
  }

  async onCancelButton() {
    this.onCancel.emit();
  }

  public onNextSelected(event: { stepper: C8yStepper; step: CdkStep }): void {

    console.log("OnNextSelected", event.step.label, this.mapping)

    if (event.step.label == "Define topic") {
      this.substitutions = '';
      console.log("Populate jsonPath if wildcard:", isWildcardTopic(this.mapping.topic), this.mapping.substitutions.length)
      console.log("Templates from mapping:", this.mapping.target, this.mapping.source)
      if (this.propertyForm.get('topic').touched) {
        this.mapping.substitutions = [];
      }
      this.updateSubstitutions();
      this.enrichTemplates();
      this.editorTarget.setSchema(getSchema(this.mapping.targetAPI), null);
    } else if (event.step.label == "Define templates") {
      console.log("Templates source from editor:", this.templateSource, this.editorSource.getText(), this.getCurrentMapping())
      this.dataTesting = this.editorSource.get();
    } else if (event.step.label == "Test mapping") {

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
    if (isWildcardTopic(this.mapping.topic)) {
      this.templateSource = {
        ...this.templateSource,
        _DEVICE_IDENT_: "909090"
      };
    }
    this.templateTarget = JSON.parse(this.mapping.target);
    if (!this.editMode) {
      this.templateTarget = JSON.parse(SAMPLE_TEMPLATES[this.mapping.targetAPI]);
      console.log("Sample template", this.templateTarget, getSchema(this.mapping.targetAPI));
    }
    if (this.mapping.targetAPI == API.INVENTORY) {
      this.templateTarget = {
        ...this.templateTarget,
        _DEVICE_IDENT_: "909090"
      };
    }
  }

  async onSnoopedSourceTemplates() {
    if (this.snoopedTemplateCounter >= this.mapping.snoopedTemplates.length) {
      this.snoopedTemplateCounter = 0;
    }
    this.templateSource = JSON.parse(this.mapping.snoopedTemplates[this.snoopedTemplateCounter]);
    //add dummy field "_DEVICE_IDENT_" to use for mapping the device identifier form the topic ending
    if (isWildcardTopic(this.mapping.topic)) {
      this.templateSource = {
        ...this.templateSource,
        _DEVICE_IDENT_: "909090"
      };
    }
    // disable further snooping for this template
    this.mapping.snoopTemplates = SnoopStatus.STOPPED;
    this.snoopedTemplateCounter++;
  }

  public onAddSubstitution() {
    this.sourcePathMissing = (this.pathSource == '');
    this.targetPathMissing = (this.pathTarget == '');
    console.log("New substitution", this.sourcePathMissing, this.targetPathMissing);
    if (this.pathSource != '' && this.pathTarget != '') {
      let sub: MappingSubstitution = {
        pathSource: this.pathSource,
        pathTarget: this.pathTarget,
        definesIdentifier: this.definesIdentifier
      }
      this.addSubstitution(sub);
      this.selectedSubstitution = -1;
      console.log("New substitution", sub);
      this.pathSource = '';
      this.pathTarget = '';
      this.definesIdentifier = false;
    }
  }

  public onDeleteSubstitutions() {
    this.mapping.substitutions = [];
    this.substitutions = "";
    this.updateSubstitutions();
    console.log("Cleared substitutions!");
  }

  public onDeleteSubstitution() {
    console.log("Delete marked substitution", this.selectedSubstitution);
    if (this.selectedSubstitution < this.mapping.substitutions.length) {
      this.mapping.substitutions.splice(this.selectedSubstitution , 1);
      this.selectedSubstitution = -1;
    }
    this.updateSubstitutions();
  }


  private updateSubstitutions() {
    this.substitutions = ''
    if (this.mapping.substitutions.length == 0 && (isWildcardTopic(this.mapping.topic) || this.mapping.indexDeviceIdentifierInTemplateTopic != -1)) {
      if (this.mapping.targetAPI != API.INVENTORY) {
        this.mapping.substitutions.push(
          { pathSource: TOKEN_DEVICE_TOPIC, pathTarget: "source.id", definesIdentifier: true });
      } else {
        // if creating new device then the json body contains only a dummy field
        this.mapping.substitutions.push(
          { pathSource: TOKEN_DEVICE_TOPIC, pathTarget: TOKEN_DEVICE_TOPIC, definesIdentifier: true });
      }
    }
    this.mapping.substitutions.forEach(s => {
      console.log("Update substitution:", s.pathSource, s.pathTarget, this.mapping.substitutions?.length);
      let marksDeviceIdentifier = (s.definesIdentifier ? "* " : "");
      this.substitutions = this.substitutions + `[ ${marksDeviceIdentifier}${s.pathSource} -> ${s.pathTarget} ]`;
    });
  }


  private addSubstitution(sub: MappingSubstitution) {

    if (sub.pathTarget == "source.id") {
      sub.definesIdentifier = true;
    }
    // test 1
    // test if mapping for sub.pathTarget already exists. Then ignore the new substitution. User has to remove the old substitution.
    let overwriteIndex = -1;
    this.mapping.substitutions.forEach((s, index) => {
      if (sub.pathTarget == s.pathTarget) {
        overwriteIndex = index;
      }
    })

    if (overwriteIndex != -1) {
      const initialState = {
        substitution: this.mapping.substitutions[overwriteIndex]
      }
      const overwriteModalRef: BsModalRef = this.bsModalService.show( OverwriteSubstitutionModalComponent, { initialState });
      overwriteModalRef.content.closeSubject.subscribe(
         (overwrite: boolean) => {
          // test 2
          // only one susbsitution can define the deviceIdentifier, thus set the others to false
          this.mapping.substitutions.forEach(s => {
            if (sub.definesIdentifier && s.definesIdentifier) s.definesIdentifier = false;
          })
          console.log("Overwriting I:", overwrite, overwrite, this.mapping.substitutions);
          if (overwrite){
            this.mapping.substitutions[overwriteIndex] = sub;
          }
          console.log("Overwriting II:", overwrite, overwrite, this.mapping.substitutions);
        }
      );
    } else {
      // test 2
      // only one susbsitution can define the deviceIdentifier, thus set the others to false
      this.mapping.substitutions.forEach(s => {
        if (sub.definesIdentifier && s.definesIdentifier) s.definesIdentifier = false;
      })
      this.mapping.substitutions.push(sub);
    }

    this.updateSubstitutions();
  }

  public onSelectSubstitution() {
    if (this.selectedSubstitution >= this.mapping.substitutions.length - 1) {
      this.selectedSubstitution = -1;
      this.paletteCounter = -1;
    }
    if (this.paletteCounter == this.COLOR_PALETTE.length - 1) {
      this.paletteCounter = -1;
    }

    this.selectedSubstitution++;
    this.paletteCounter++;
    this.sourcePathMissing = false;
    this.targetPathMissing = false;
    this.nextColor = this.COLOR_PALETTE[this.paletteCounter];

    // reset background color of old selection list
    for (let item of this.selectionList) {
      item.setAttribute('style', null);
    }
    this.pathSource = this.mapping.substitutions[this.selectedSubstitution].pathSource;
    this.updateSourceExpressionResult(this.mapping.substitutions[this.selectedSubstitution].pathSource);
    this.pathTarget = this.mapping.substitutions[this.selectedSubstitution].pathTarget;
    this.definesIdentifier = this.mapping.substitutions[this.selectedSubstitution].definesIdentifier;
    this.setSelectionToPath(this.editorSource, this.mapping.substitutions[this.selectedSubstitution].pathSource)
    this.setSelectionToPath(this.editorTarget, this.mapping.substitutions[this.selectedSubstitution].pathTarget)
    console.log("Found querySelectorAll elements:", this.elementRef.nativeElement.querySelectorAll('.jsoneditor-selected'))
    //this.selectionList  = this.elementRef.nativeElement.getElementsByClassName('jsoneditor-selected');
    this.selectionList = this.elementRef.nativeElement.querySelectorAll('.jsoneditor-selected');
    for (let item of this.selectionList) {
      item.setAttribute('style', `background: ${this.nextColor};`);
    }

    console.log("Show substitutions!");
  }

}