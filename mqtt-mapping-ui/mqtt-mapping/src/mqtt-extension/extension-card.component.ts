import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { IManagedObject } from '@c8y/client';
import { AlertService } from '@c8y/ngx-components';
import { BrokerConfigurationService } from '../mqtt-configuration/broker-configuration.service';
import { ExtensionStatus } from '../shared/mapping.model';
import { ExtensionService } from './extension.service';

@Component({
  selector: 'c8y-extension-card',
  templateUrl: './extension-card.component.html'
})
export class ExtensionCardComponent implements OnInit {
  @Input() app: IManagedObject;
  @Output() onAppDeleted: EventEmitter<void> = new EventEmitter();

  ExtensionStatus = ExtensionStatus;
  externalExtensionEnabled: boolean = true;

  constructor(
    private extensionService: ExtensionService,
    private alertService: AlertService,
    private router: Router,
    private activatedRoute: ActivatedRoute,
    private configurationService: BrokerConfigurationService
  ) {}

  async ngOnInit() {
    this.externalExtensionEnabled = (await this.configurationService.getServiceConfiguration()).externalExtensionEnabled;
 
  }

  async detail() {
    //this.router.navigateByUrl(`/mqtt-mapping/extensions/${this.app.id}`);
    this.router.navigate(['properties/', this.app.id], {relativeTo: this.activatedRoute});
    console.log("Details clicked now:", this.app.id );
  }

  async delete() {
    try {
      await this.extensionService.deleteExtension(this.app);
      this.onAppDeleted.emit();
    } catch (ex) {
      if (ex) {
        this.alertService.addServerFailure(ex);
      }
    }
  }
}
