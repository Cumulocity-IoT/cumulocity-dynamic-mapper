/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { NO_ERRORS_SCHEMA } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ApplicationService } from '@c8y/client';
import { AlertService } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { ExtensionService } from '../extension.service';
import { AddExtensionComponent } from './add-extension.component';

describe('AddExtensionComponent upload state', () => {
  let component: AddExtensionComponent;
  let upload$: Subject<{ percentage: number }>;
  let extensionService: jasmine.SpyObj<ExtensionService>;
  let alertService: jasmine.SpyObj<AlertService>;

  const file = new File(['jar'], 'dynamic-mapper-extension.jar');

  beforeEach(() => {
    upload$ = new Subject();
    extensionService = jasmine.createSpyObj('ExtensionService', ['uploadProcessorExtensionWithProgress$']);
    extensionService.uploadProcessorExtensionWithProgress$.and.returnValue(upload$.asObservable() as never);
    alertService = jasmine.createSpyObj('AlertService', ['success', 'addServerFailure']);

    TestBed.configureTestingModule({
      providers: [
        { provide: ExtensionService, useValue: extensionService },
        { provide: AlertService, useValue: alertService },
        { provide: ApplicationService, useValue: jasmine.createSpyObj('ApplicationService', ['getHref']) }
      ]
    });
    TestBed.overrideComponent(AddExtensionComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });
    component = TestBed.createComponent(AddExtensionComponent).componentInstance;
  });

  it('uploads under the file name without ".jar" and passes the overwrite choice on', async () => {
    component.overwrite = true;

    await component.onFile(file);

    expect(extensionService.uploadProcessorExtensionWithProgress$).toHaveBeenCalledWith(file, {
      name: 'dynamic-mapper-extension',
      overwrite: true
    });
  });

  it('aborts the running upload when the dialog is dismissed', async () => {
    await component.onFile(file);
    let aborted = false;
    // the subscription's teardown is what aborts the XMLHttpRequest
    component['uploadSubscription'].add(() => (aborted = true));

    component.onDismiss();

    expect(aborted).toBeTrue();
  });

  it('does not show the success view while the upload runs', async () => {
    await component.onFile(file);

    expect(component.isAppCreated).toBeFalse();
    expect(component.isLoading).toBeTrue();
    expect(alertService.success).not.toHaveBeenCalled();
  });

  it('shows the success view, with the extension name, only after the upload completed', async () => {
    await component.onFile(file);
    upload$.next({ percentage: 100 });
    upload$.complete();

    expect(component.isAppCreated).toBeTrue();
    expect(component.isLoading).toBeFalse();
    expect(component.extensionName).toBe('dynamic-mapper-extension');
    expect(alertService.success).toHaveBeenCalledTimes(1);
  });

  it('stays on the upload view with the error when the upload fails (e.g. 401)', async () => {
    await component.onFile(file);
    upload$.error({ data: { message: 'Access Denied' } });

    expect(component.isAppCreated).toBeFalse();
    expect(component.isLoading).toBeFalse();
    expect(component.errorMessage).toBe('Access Denied');
    expect(component.extensionName).toBeNull();
    expect(alertService.success).not.toHaveBeenCalled();
  });
});
