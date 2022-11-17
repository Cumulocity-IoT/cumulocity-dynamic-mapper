import { Component, OnInit, ViewChild } from '@angular/core';
import {
  ConfirmModalComponent,
  gettext,
  ModalLabels,
  Status,
  StatusType,
} from '@c8y/ngx-components';
import { TranslateService } from '@ngx-translate/core';
import { Subject } from 'rxjs';

@Component({
  selector: 'terminate-connection-modal',
  templateUrl: 'terminate-connection-modal.component.html',
})
export class TerminateBrokerConnectionModalComponent implements OnInit {
  @ViewChild('modalRef', { static: false }) modalRef: ConfirmModalComponent;
  message: string;
  closeSubject: Subject<boolean> = new Subject();
  labels: ModalLabels = { ok: gettext('Disconnect'), cancel: gettext('Cancel') };
  title = gettext('Disconnect');
  status: StatusType = Status.WARNING;

  constructor(private translateService: TranslateService) {}

  ngOnInit() {
    this.message = this.translateService.instant(
      gettext('You are about to diconnect. Do you want to proceed?')
    );
  }

  async ngAfterViewInit() {
    try {
      await this.modalRef.result;
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
