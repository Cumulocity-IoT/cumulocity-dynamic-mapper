/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { NO_ERRORS_SCHEMA } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { Subject } from 'rxjs';
import { TestingComponent } from './testing.component';
import { TestingDeviceService } from '../testing-device.service';

describe('TestingComponent', () => {
  let refreshData: Subject<boolean>;
  let reload: jasmine.Spy;

  beforeEach(() => {
    refreshData = new Subject<boolean>();
    reload = jasmine.createSpy('reload');
    TestBed.configureTestingModule({
      imports: [TestingComponent],
      providers: [
        { provide: TestingDeviceService, useValue: { refreshData$: refreshData.asObservable(), getPagination: () => ({}) } },
        { provide: ActivatedRoute, useValue: { snapshot: { data: {} } } }
      ]
    });
    // CoreModule cannot be created in TestBed (NG0201), and these specs only exercise the class.
    TestBed.overrideComponent(TestingComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });
  });

  it('reloads the grid when test devices were deleted', () => {
    const fixture = TestBed.createComponent(TestingComponent);
    Object.assign(fixture.componentInstance, { deviceGrid: () => ({ reload }) });

    refreshData.next(true);

    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('stops listening once destroyed, so a root-service signal does not reach a dead grid', () => {
    const fixture = TestBed.createComponent(TestingComponent);
    Object.assign(fixture.componentInstance, { deviceGrid: () => ({ reload }) });

    fixture.destroy();
    refreshData.next(true);

    expect(reload).not.toHaveBeenCalled();
    expect(refreshData.observed).toBeFalse();
  });

  it('ignores a signal that arrives before the grid view exists', () => {
    TestBed.createComponent(TestingComponent);

    expect(() => refreshData.next(true)).not.toThrow();
  });
});
