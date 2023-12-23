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
import { Component, Input, OnInit, ViewChild } from "@angular/core";
import {
  ConfirmModalComponent,
  gettext,
  ModalLabels,
  Status,
  StatusType,
} from "@c8y/ngx-components";
import { TranslateService } from "@ngx-translate/core";
import { Subject } from "rxjs";
import {
  Direction,
  MappingSubstitution,
} from "../../shared";
import { definesDeviceIdentifier } from "../shared/util";

@Component({
  selector: "d11r-mapping-overwrite-substitution-modal",
  templateUrl: "overwrite-substitution-modal.component.html",
})
export class OverwriteSubstitutionModalComponent implements OnInit {
  @ViewChild("overwriteSubstitutionRef", { static: false })
  overwriteSubstitutionRef: ConfirmModalComponent;

  @Input()
  substitution: MappingSubstitution;

  message1: string;
  message2: string;
  targetAPI: string;
  direction: Direction;
  substitutionText: string;
  closeSubject: Subject<boolean> = new Subject();
  labels: ModalLabels = { ok: gettext("Overwrite"), cancel: gettext("Cancel") };
  title = gettext("Overwrite");
  status: StatusType = Status.WARNING;

  constructor(private translateService: TranslateService) {}

  ngOnInit() {
    this.message1 = this.translateService.instant(
      gettext("You are about to overwrite an exting substitution:")
    );
    this.message2 = this.translateService.instant(
      gettext("Do you want to proceed?")
    );
    let marksDeviceIdentifier = definesDeviceIdentifier(
      this.targetAPI,
      this.substitution,
      this.direction
    )
      ? "* "
      : "";
    this.substitutionText = `[ ${marksDeviceIdentifier}${this.substitution.pathSource} -> ${this.substitution.pathTarget} ]`;
  }

  async ngAfterViewInit() {
    try {
      await this.overwriteSubstitutionRef.result;
      this.onClose();
    } catch (error) {
      this.onDismiss();
    }
  }

  onClose() {
    this.closeSubject.next(true);
    this.closeSubject.complete();
  }

  onDismiss() {
    this.closeSubject.next(false);
    this.closeSubject.complete();
  }

  ngOnDestroy() {
    this.closeSubject.complete();
  }
}
