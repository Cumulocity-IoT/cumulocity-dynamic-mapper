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
import { SnoopStatus } from '../../shared/configuration.model';

@Component({
  selector: 'mapping-snooping-modal',
  templateUrl: 'snooping-modal.component.html',
})
export class SnoopingModalComponent implements OnInit {
  @ViewChild('snoopingRef', { static: false }) snoopingRef: ConfirmModalComponent;
  
  @Input()
  snoopStatus: SnoopStatus

  SnoopStatus = SnoopStatus; 
  labels: ModalLabels = { ok: gettext('Confirm') };
  title = gettext('Snooping');
  status: StatusType = Status.INFO;
  closeSubject: Subject<boolean> = new Subject();

  constructor(private translateService: TranslateService) {}

  ngOnInit() {
  }

  async ngAfterViewInit() {
    try {
      await this.snoopingRef.result;
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
