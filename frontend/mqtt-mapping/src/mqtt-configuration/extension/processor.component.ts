
import { Component, OnInit } from '@angular/core';
import { IManagedObject } from '@c8y/client';
import { WizardService, WizardConfig } from '@c8y/ngx-components';
import { BehaviorSubject, Observable } from 'rxjs';
import { shareReplay, switchMap, tap } from 'rxjs/operators';
import { ProcessorService } from './processor.service';
import { ModalOptions } from 'ngx-bootstrap/modal';


@Component({
  selector: 'app-processor',
  templateUrl: './processor.component.html',
  styleUrls: ['./processor.component.css']
})
export class ProcessorComponent implements OnInit {
  reloading: boolean = false;
  reload$: BehaviorSubject<void> = new BehaviorSubject(null);

  extensions$: Observable<IManagedObject> = this.reload$.pipe(
    tap(() => (this.reloading = true)),
    switchMap(() => this.processorService.getWebExtensions()),
    tap(console.log),
    tap(() => (this.reloading = false)),
    shareReplay()
  );

  listClass: string;

  constructor(
    private processorService: ProcessorService,
    private wizardService: WizardService
  ) { }

  ngOnInit() {
    this.loadExtensions();
  }

  loadExtensions() {
    this.reload$.next();
  }

  addExtension() {
    const wizardConfig: WizardConfig = {
      headerText: 'Add Processor Extension',
      headerIcon: 'c8y-atom'
    };

    const initialState: any = {
      wizardConfig,
      id: 'uploadProcessorExtension'
    };

    const modalOptions: ModalOptions = { initialState };

    const modalRef = this.wizardService.show(modalOptions);
    modalRef.content.onClose.subscribe(() => {
      this.loadExtensions();
    });
  }
}
