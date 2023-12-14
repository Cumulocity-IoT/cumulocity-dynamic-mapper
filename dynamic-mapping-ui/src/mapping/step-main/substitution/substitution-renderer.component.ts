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
import {
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnInit,
  Output,
  ViewEncapsulation,
} from "@angular/core";
import {
  Direction,
  MappingSubstitution,
  definesDeviceIdentifier,
} from "../../../shared";
import { isDisabled } from "../util";
import { EditorMode } from "../stepper-model";

@Component({
  selector: "d11r-mapping-substitution-renderer",
  templateUrl: "substitution-renderer.component.html",
  styleUrls: ["./substitution-renderer.style.css"],
  encapsulation: ViewEncapsulation.None,
})
export class SubstitutionRendererComponent implements OnInit {
  @Input()
  substitutions: MappingSubstitution[] = [];
  @Input()
  targetAPI: string;
  @Input()
  settings: any;
  @Input()
  direction: Direction;

  @Output() onSelect = new EventEmitter<number>();
  @Output() onDelete = new EventEmitter<number>();
  @Output() onEdit = new EventEmitter<number>();

  public id = Math.floor(Math.random() * 1000000);
  definesDeviceIdentifier = definesDeviceIdentifier;
  isDisabled = isDisabled;
  EditorMode = EditorMode;

  constructor(private elementRef: ElementRef) {}

  ngOnInit() {
    //console.log ("Setting for renderer:", this.setting)
  }

  onSubstitutionSelect(index: number) {
    console.log("Selected substitution:", index);
    this.settings.selectedSubstitutionIndex = index;
    this.onSelect.emit(index);
  }

  public scrollToSubstitution(i: number) {
    i++;
    if (!i || i < 0 || i >= this.substitutions.length) {
      i = 0;
    }
    console.log("Scroll to:", i);
    this.elementRef.nativeElement
      .querySelector(`#sub-${this.id}-${i}`)
      .scrollIntoView();
  }

  public onSubstitutionDelete(index: number) {
    console.log("Delete substitution:", index);
    this.settings.selectedSubstitutionIndex = index;
    this.onDelete.emit(index);
  }
  
}
