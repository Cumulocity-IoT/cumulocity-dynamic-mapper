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
import { definesDeviceIdentifier } from "../../shared/util";
import { Direction, MappingSubstitution } from "../../shared/mapping.model";

@Component({
  selector: "mapping-overwrite-device-identifier-modal",
  templateUrl: "overwrite-device-identifier-modal.component.html",
})
export class OverwriteDeviceIdentifierModalComponent implements OnInit {
  @ViewChild("overwriteDeviceIdentifierRef", { static: false })
  overwriteDeviceIdentifierRef: ConfirmModalComponent;

  @Input()
  substitutionOld: MappingSubstitution;
  @Input()
  substitutionNew: MappingSubstitution;
  @Input()
  targetAPI: string;

  message1: string;
  message2: string;
  message3: string;
  direction: Direction;
  substitutionNewText: string;
  substitutionOldText: string;
  closeSubject: Subject<boolean> = new Subject();
  labels: ModalLabels = {
    ok: gettext("Overwrite"),
    cancel: gettext("Keep old Device Identifier"),
  };
  title = gettext("Overwrite");
  status: StatusType = Status.WARNING;

  constructor(private translateService: TranslateService) {}

  ngOnInit() {
    this.message1 = this.translateService.instant(
      gettext(
        "You are about to overwrite the defice identifier of the existing substitution:"
      )
    );
    this.message2 = this.translateService.instant(
      gettext("with the new substitution:")
    );
    this.message3 = this.translateService.instant(
      gettext("Do you want to proceed?")
    );
    let marksDeviceIdentifierOld = definesDeviceIdentifier(
      this.targetAPI,
      this.substitutionOld,
      this.direction
    )
      ? "* "
      : "";
    this.substitutionOldText = `[ ${marksDeviceIdentifierOld}${this.substitutionOld.pathSource} -> ${this.substitutionOld.pathTarget} ]`;
    let marksDeviceIdentifierNew = definesDeviceIdentifier(
      this.targetAPI,
      this.substitutionNew,
      this.direction
    )
      ? "* "
      : "";
    this.substitutionNewText = `[ ${marksDeviceIdentifierNew}${this.substitutionNew.pathSource} -> ${this.substitutionNew.pathTarget} ]`;
  }

  async ngAfterViewInit() {
    try {
      await this.overwriteDeviceIdentifierRef.result;
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
}
