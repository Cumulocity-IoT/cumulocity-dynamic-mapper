import { Component, Input, OnInit, ViewChild } from '@angular/core';
import {
  ConfirmModalComponent,
  gettext,
  ModalLabels,
  Status,
  StatusType,
} from '@c8y/ngx-components';
import { TranslateService } from '@ngx-translate/core';
import { Subject } from 'rxjs';
import { MappingSubstitution } from '../../shared/configuration.model';

@Component({
  selector: 'mapping-overwrite-device-identifier-modal',
  templateUrl: 'overwrite-device-identifier-modal.component.html',
})
export class OverwriteDeviceIdentifierModalComponent implements OnInit {
  @ViewChild('overwriteDeviceIdentifierRef', { static: false }) overwriteDeviceIdentifierRef: ConfirmModalComponent;

  @Input()
  substitutionOld: MappingSubstitution;
  @Input()
  substitutionNew: MappingSubstitution;

  message1: string;
  message2: string;
  message3: string;
  substitutionNewText: string;
  substitutionOldText: string;
  closeSubject: Subject<boolean> = new Subject();
  labels: ModalLabels = { ok: gettext('Overwrite'), cancel: gettext('Keep old Device Identifier') };
  title = gettext('Overwrite');
  status: StatusType = Status.WARNING;

  constructor(private translateService: TranslateService) { }

  ngOnInit() {
    this.message1 = this.translateService.instant(
      gettext('You are about to overwrite the defice identifier of the existing substitution:'));
    this.message2 = this.translateService.instant(
      gettext('with the new substitution:'));
    this.message3 = this.translateService.instant(
      gettext('Do you want to proceed?'));
    let marksDeviceIdentifierOld = (this.substitutionOld.definesIdentifier ? "* " : "");
    this.substitutionOldText = `[ ${marksDeviceIdentifierOld}${this.substitutionOld.pathSource} -> ${this.substitutionOld.pathTarget} ]`;
    let marksDeviceIdentifierNew = (this.substitutionNew.definesIdentifier ? "* " : "");
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
