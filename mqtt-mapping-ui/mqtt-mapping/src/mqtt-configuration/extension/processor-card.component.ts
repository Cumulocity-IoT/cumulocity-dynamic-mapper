import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { IManagedObject } from '@c8y/client';
import { AlertService } from '@c8y/ngx-components';
import { ExtensionStatus } from '../../shared/mapping.model';
import { ProcessorService } from './processor.service';

@Component({
  selector: 'c8y-processor-card',
  templateUrl: './processor-card.component.html'
})
export class ProcessorCardComponent implements OnInit {
  @Input() app: IManagedObject;
  @Output() onAppDeleted: EventEmitter<void> = new EventEmitter();

  ExtensionStatus = ExtensionStatus;

  constructor(
    private processorService: ProcessorService,
    private alertService: AlertService,
  ) {}

  async ngOnInit() {
  }

  async delete() {
    try {
      await this.processorService.deleteExtension(this.app);
      this.onAppDeleted.emit();
    } catch (ex) {
      if (ex) {
        this.alertService.addServerFailure(ex);
      }
    }
  }
}
