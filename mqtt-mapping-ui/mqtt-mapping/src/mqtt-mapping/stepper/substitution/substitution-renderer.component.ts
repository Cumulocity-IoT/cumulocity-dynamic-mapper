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
import { Component, ElementRef, EventEmitter, Input, OnInit, Output, ViewChild, ViewEncapsulation } from '@angular/core';
import { definesDeviceIdentifier } from '../../../shared/util';
import { Mapping, MappingSubstitution } from '../../../shared/mapping.model';


@Component({
  selector: 'mapping-substitution-renderer',
  templateUrl: 'substitution-renderer.component.html',
  styleUrls: ['./substitution-renderer.style.css',
  ],
  encapsulation: ViewEncapsulation.None,
})

export class SubstitutionRendererComponent implements OnInit {

  @Input()
  substitutions: MappingSubstitution[] = [];
  @Input()
  targetAPI: string;
  @Input()
  setting: any;

  @Output() onSelect = new EventEmitter<number>();

  public id =  Math.floor(Math.random() * 1000000);
  definesDeviceIdentifier = definesDeviceIdentifier;

  constructor(  private elementRef: ElementRef,) { }

  ngOnInit() {
    console.log ("Setting for renderer:", this.setting)
  }

  onSubstitutionSelected (index: number) {
    console.log("Selected substitution:", index);
    this.setting.selectedSubstitutionIndex = index;
    this.onSelect.emit(index);
  }

  public scrollToSubstitution(i: number){
    i++;
    if (!i || i < 0 || i >= this.substitutions.length) {
      i = 0;
    }
    console.log ("Scroll to:", i);
    this.elementRef.nativeElement.querySelector(`#sub-${this.id}-${i}` ).scrollIntoView();
  }
}
