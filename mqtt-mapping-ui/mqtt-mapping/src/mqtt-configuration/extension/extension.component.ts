
import { Component, OnInit } from '@angular/core';
import { IManagedObject, IResultList } from '@c8y/client';
import { WizardService, WizardConfig } from '@c8y/ngx-components';
import { BehaviorSubject, Observable } from 'rxjs';
import { shareReplay, switchMap, tap } from 'rxjs/operators';
import { ExtensionService } from './extension.service';
import { ModalOptions } from 'ngx-bootstrap/modal';
import { BrokerConfigurationService } from '../broker-configuration.service';
import { Operation } from '../../shared/mapping.model';


@Component({
  selector: 'app-extension',
  templateUrl: './extension.component.html',
  styleUrls: ['./extension.component.css']
})
export class ExtensionComponent implements OnInit {
  reloading: boolean = false;
  reload$: BehaviorSubject<void> = new BehaviorSubject(null);

  extensions$: Observable<IResultList<IManagedObject>> = this.reload$.pipe(
    tap(() => (this.reloading = true)),
    switchMap(() => this.extensionService.getExtensionsEnriched()),
    tap(console.log),
    tap(() => (this.reloading = false)),
    shareReplay()
  );

  listClass: string;

  constructor(
    private wizardService: WizardService,
    private extensionService: ExtensionService,
    private configurationService: BrokerConfigurationService
  ) { }

  ngOnInit() {
    this.loadExtensions();
    this.extensions$.subscribe( exts => {
      console.log("New extenions:", exts);
    })
  }

  loadExtensions() {
    this.reload$.next();
  }

  reloadExtensions() {
    this.configurationService.runOperation(Operation.RELOAD_EXTENSIONS);
    this.reload$.next();
  }

  addExtension() {
    const wizardConfig: WizardConfig = {
      headerText: 'Add Extension Extension',
      headerIcon: 'plugin'
    };

    const initialState: any = {
      wizardConfig,
      id: 'uploadExtensionWizard'
    };

    const modalOptions: ModalOptions = { initialState };

    const modalRef = this.wizardService.show(modalOptions);
    modalRef.content.onClose.subscribe(() => {
      this.loadExtensions();
    });
  }
}
