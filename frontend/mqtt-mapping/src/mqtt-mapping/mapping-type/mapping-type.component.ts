import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { WizardComponent } from '@c8y/ngx-components';
import { MappingType } from '../../shared/mapping.model';

@Component({
  selector: 'app-mapping-type',
  templateUrl: './mapping-type.component.html',
})
export class MappingTypeComponent implements OnInit {

  headerText: string;
  headerIcon: string;
  bodyHeaderText: string;

  canOpenInBrowser: boolean = false;
  errorMessage: string;
  MappingType = MappingType;
  mappingType: MappingType;

  constructor(
    private wizardComponent: WizardComponent
  ) {}

  ngOnInit(): void {
    this.headerText = this.wizardComponent.wizardConfig.headerText;
    this.headerIcon = this.wizardComponent.wizardConfig.headerIcon;
    this.bodyHeaderText = this.wizardComponent.wizardConfig.bodyHeaderText;
  }
  cancel() {
    this.wizardComponent.close();
  }
  done() {
    this.wizardComponent.close(this.mappingType);
  }
  onSelect(t){
    this.mappingType = t;
    this.wizardComponent.close(this.mappingType);
  }
}
