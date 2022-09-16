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
import { MappingSubstitution } from '../../shared/mqtt-configuration.model';

@Component({
  selector: 'overwrite-substitution-modal',
  templateUrl: 'overwrite-substitution-modal.component.html',
})
export class OverwriteSubstitutionModalComponent implements OnInit {
  @ViewChild('overwriteRef', { static: false }) overwriteRef: ConfirmModalComponent;

  @Input()
  substitution: MappingSubstitution;

  message1: string;
  message2: string;
  substitutionText: string;
  closeSubject: Subject<boolean> = new Subject();
  labels: ModalLabels = { ok: gettext('Overwrite'), cancel: gettext('Cancel') };
  title = gettext('Overwrite');
  status: StatusType = Status.DANGER;

  constructor(private translateService: TranslateService) {}

  ngOnInit() {
    this.message1 = `You are about to overwrite an exting substitution:`;
    this.message2 = `Do you want to proceed?`;
    this.substitutionText = `[${this.substitution.pathSource}-> ${this.substitution.pathTarget}]`;
  
  }

  async ngAfterViewInit() {
    try {
      await this.overwriteRef.result;
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
    this.closeSubject.complete();
  }
}
