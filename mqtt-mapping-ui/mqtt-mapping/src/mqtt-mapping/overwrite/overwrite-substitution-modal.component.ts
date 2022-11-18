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
import { definesDeviceIdentifier } from '../../shared/util';
import { MappingSubstitution } from '../../shared/mapping.model';
import { APIRendererComponent } from '../renderer/api.renderer.component';

@Component({
  selector: 'mapping-overwrite-substitution-modal',
  templateUrl: 'overwrite-substitution-modal.component.html',
})
export class OverwriteSubstitutionModalComponent implements OnInit {
  @ViewChild('overwriteSubstitutionRef', { static: false }) overwriteSubstitutionRef: ConfirmModalComponent;

  @Input()
  substitution: MappingSubstitution;

  message1: string;
  message2: string;
  targetAPI: string;
  substitutionText: string;
  closeSubject: Subject<boolean> = new Subject();
  labels: ModalLabels = { ok: gettext('Overwrite'), cancel: gettext('Cancel') };
  title = gettext('Overwrite');
  status: StatusType = Status.WARNING;

  constructor(private translateService: TranslateService) {}

  ngOnInit() {
    this.message1 = this.translateService.instant(
      gettext('You are about to overwrite an exting substitution:'));;
    this.message2 = this.translateService.instant(
      gettext('Do you want to proceed?'));
    let marksDeviceIdentifier = (definesDeviceIdentifier(this.targetAPI , this.substitution) ? "* " : "");
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
}
