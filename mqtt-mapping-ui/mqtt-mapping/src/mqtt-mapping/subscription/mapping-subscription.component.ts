/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
import { CdkStep } from '@angular/cdk/stepper';
import { AfterContentChecked, Component, ElementRef, EventEmitter, Input, OnInit, Output, ViewChild, ViewEncapsulation } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { AlertService, C8yStepper } from '@c8y/ngx-components';
import * as _ from 'lodash';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject } from 'rxjs';
import { debounceTime } from "rxjs/operators";
import { API, Direction, Extension, Mapping, MappingSubstitution, QOS, RepairStrategy, SnoopStatus, ValidationError } from "../../shared/mapping.model";
import { checkPropertiesAreValid, checkSubstitutionIsValid, COLOR_HIGHLIGHTED, definesDeviceIdentifier, deriveTemplateTopicFromTopic, getSchema, isWildcardTopic, SAMPLE_TEMPLATES_C8Y, SCHEMA_PAYLOAD, splitTopicExcludingSeparator, TOKEN_DEVICE_TOPIC, TOKEN_TOPIC_LEVEL, whatIsIt, countDeviceIdentifiers } from "../../shared/util";
import { OverwriteSubstitutionModalComponent } from '../overwrite/overwrite-substitution-modal.component';
import { SnoopingModalComponent } from '../snooping/snooping-modal.component';
import { JsonEditorComponent, JsonEditorOptions } from '../../shared/editor/jsoneditor.component';
import { C8YRequest } from '../processor/prosessor.model';
import { MappingService } from '../core/mapping.service';
import { BrokerConfigurationService } from '../../mqtt-configuration/broker-configuration.service';
import { IIdentified } from '@c8y/client';

@Component({
  selector: 'mapping-subscription',
  templateUrl: 'mapping-subscription.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
})

export class MappingSubscriptionComponent implements OnInit {
  
  @Input() deviceList: IIdentified;
  @Output() onCancel = new EventEmitter<any>();
  @Output() onCommit = new EventEmitter<IIdentified>();
  
  ngOnInit(): void {
    
  }

  selectionChanged(e) {
    console.log(e);
  }

  clickedUpdateSubscription() {

    this.onCommit.emit(this.deviceList);

  }

  clickedCancel() {
    
    this.onCancel.emit();

  }

}