import { CdkStep } from '@angular/cdk/stepper';
import { AfterContentChecked, Component, ElementRef, EventEmitter, Input, OnInit, Output, ViewChild, ViewEncapsulation } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { C8yStepper } from '@c8y/ngx-components';
import { JsonEditorComponent } from '@maaxgr/ang-jsoneditor';
import * as _ from 'lodash';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { Subject } from 'rxjs';
import { debounceTime } from "rxjs/operators";
import { API, Mapping, MappingSubstitution, QOS, SnoopStatus, ValidationError, LINK_DIRECT, UPLINK_API_OPTION, DOWNLINK_API_OPTION } from "../../shared/configuration.model";
import { checkPropertiesAreValid, checkSubstitutionIsValid, deriveTemplateTopicFromTopic, getSchema, isWildcardTopic, normalizeTopic, SAMPLE_TEMPLATES, SCHEMA_PAYLOAD, splitTopic, TOKEN_DEVICE_TOPIC } from "../../shared/helper";
import { OverwriteDeviceIdentifierModalComponent } from '../overwrite/overwrite-device-identifier-modal.component';
import { OverwriteSubstitutionModalComponent } from '../overwrite/overwrite-substitution-modal.component';
import { MappingService } from '../shared/mapping.service';
import { SnoopingModalComponent } from '../snooping/snooping-modal.component';

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

  API = UPLINK_API_OPTION;
  LINK_DIRECT = LINK_DIRECT;
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
      onEvent: this.setSelection.bind(this),
      schema: SCHEMA_PAYLOAD
    };

    this.editorOptionsTarget = {
      modes: ['tree', 'code'],
      statusBar: false,
      navigationBar: false,
      enableSort: false,
      enableTransform: false,
      enableSearch: false,
      onEvent: this.setSelection.bind(this),
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

  private setSelection(node: any, event: any) {
    if (event.type == "click") {
      // determine the json editor where the click happened
      let target = '';
      var eventPath = event.path || (event.composedPath && event.composedPath());
      eventPath.forEach(element => {
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
  }

  private initPropertyForm(): void {
    this.propertyForm = new FormGroup({
      id: new FormControl(this.mapping.id, Validators.required),
      direct: new FormControl(this.mapping.direct,Validators.required),
      targetAPI: new FormControl(this.mapping.targetAPI, Validators.required),
      subscriptionTopic: new FormControl(this.mapping.subscriptionTopic, Validators.required),
      templateTopic: new FormControl(this.mapping.templateTopic),
      markedDeviceIdentifier: new FormControl(this.markedDeviceIdentifier),
      active: new FormControl(this.mapping.active),
      qos: new FormControl(this.mapping.qos, Validators.required),
      mapDeviceIdentifier: new FormControl(this.mapping.mapDeviceIdentifier),
      createNonExistingDevice: new FormControl(this.mapping.createNonExistingDevice),
      updateExistingDevice: new FormControl(this.mapping.updateExistingDevice),
      externalIdType: new FormControl(this.mapping.externalIdType),
      snoopStatus: new FormControl(this.mapping.snoopStatus),
      filterType: new FormControl(this.mapping.filterType),
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
    try {
      this.sourceExpressionResult = this.mappingService.evaluateExpression(this.editorSource?.get(), path, false);
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

  onSubscriptionTopicChanged(event): void {
    this.mapping.subscriptionTopic = normalizeTopic(this.mapping.subscriptionTopic);
    this.mapping.indexDeviceIdentifierInTemplateTopic = -1;
    this.initMarkedDeviceIdentifier();
    this.mapping.templateTopic = deriveTemplateTopicFromTopic(this.mapping.subscriptionTopic);
  }

  onTemplateTopicChanged(event): void {
    this.mapping.templateTopic = normalizeTopic(this.mapping.templateTopic);
  }

  onLinkDirectChanged(event): void{
    if(this.mapping.direct){
      this.API = DOWNLINK_API_OPTION;
      this.mapping.targetAPI = this.API[0].value;
    }else{
      this.API = UPLINK_API_OPTION;
    }
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
      ... this.mapping,
      source: this.reduceTemplate(this.editorSource.get()),   //remove dummy field "_DEVICE_IDENT_", since it should not be stored
      target: this.reduceTemplate(this.editorTarget.get()),   //remove dummy field "_DEVICE_IDENT_", since it should not be stored
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
    this.mapping.tested = (this.dataTesting != '');
  }

  onSelectNextDeviceIdentifier() {
    let parts: string[] = splitTopic(this.mapping.templateTopic);
    if (parts.length > 0) {
      let nextIndex = this.mapping.indexDeviceIdentifierInTemplateTopic;
      nextIndex++;
      if (nextIndex >= parts.length) {
        nextIndex = 0;
      }
      while (nextIndex < parts.length && parts[nextIndex] == "/") {
        nextIndex++;
        if (nextIndex >= parts.length) {
          nextIndex = 0;
        }
      }
      if (parts[nextIndex] != "/") {
        this.markedDeviceIdentifier = parts[nextIndex];
        this.mapping.indexDeviceIdentifierInTemplateTopic = nextIndex;
      }
    }
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
      this.templateTarget = this.expandTemplate(this.templateTarget);
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

    const initialState = {
      snoopStatus: this.mapping.snoopStatus
    }
    if (this.mapping.snoopStatus == SnoopStatus.ENABLED && this.mapping.snoopedTemplates.length == 0) {
      console.log("Ready to snoop ...");
      const modalRef: BsModalRef = this.bsModalService.show(SnoopingModalComponent, { initialState });
      modalRef.content.closeSubject.subscribe((confirm: boolean) => {
        if (confirm) {
          this.onCommit.emit(this.getCurrentMapping());
        } else {
          this.mapping.snoopStatus = SnoopStatus.NONE
        }
      })
    } else if (this.mapping.snoopStatus == SnoopStatus.STARTED){
      console.log("Continue snoop ...?");
      const modalRef: BsModalRef = this.bsModalService.show(SnoopingModalComponent, { initialState });
      modalRef.content.closeSubject.subscribe((confirm: boolean) => {
        if (confirm) {
          this.mapping.snoopStatus = SnoopStatus.STOPPED
        } else {
          this.onCancel.emit();
        }
      })
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
    //check direct , if download
    if(this.mapping.direct){
      if(this.mapping.source.length <= 2){
        let _target = this.templateTarget;
        let _source = this.templateSource;

        this.templateTarget = _source;
        this.templateSource = _target;
      }
      this.API = DOWNLINK_API_OPTION;
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
    this.mapping.snoopStatus = SnoopStatus.STOPPED;
    this.snoopedTemplateCounter++;
  }

  async onTargetAPIChanged(evt) {
    this.mapping.target = SAMPLE_TEMPLATES[this.mapping.targetAPI];
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
    if (sub.pathTarget == "source.id" || (sub.pathTarget == TOKEN_DEVICE_TOPIC && this.mapping.targetAPI == API.INVENTORY)) {
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
          const modalRef: BsModalRef = this.bsModalService.show(OverwriteDeviceIdentifierModalComponent, { initialState });
          modalRef.content.closeSubject.subscribe(
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
      const modalRef: BsModalRef = this.bsModalService.show(OverwriteSubstitutionModalComponent, { initialState });
      modalRef.content.closeSubject.subscribe(
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
    if (selected < this.mapping.substitutions.length && selected > -1) {
      // reset background color of old selection list
      for (let item of this.selectionList) {
        item.setAttribute('style', null);
      }
      const sel = this.mapping.substitutions[selected];
      this.currentSubstitution = new MappingSubstitution(sel.pathSource, sel.pathTarget, sel.definesIdentifier);
      this.updateSourceExpressionResult(this.currentSubstitution.pathSource);
      this.setSelectionToPath(this.editorSource, this.currentSubstitution.pathSource);
      this.setSelectionToPath(this.editorTarget, this.currentSubstitution.pathTarget);
      this.selectionList = this.elementRef.nativeElement.querySelectorAll('.jsoneditor-selected');
      for (let item of this.selectionList) {
        item.setAttribute('style', `background: ${this.COLOR_HIGHLIGHTED};`);
      }
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