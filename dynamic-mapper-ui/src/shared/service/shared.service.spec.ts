/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { Direction } from '../mapping/mapping.model';
import { SharedService } from './shared.service';

describe('SharedService reload signals', () => {
  let service: SharedService;
  let inbound: number;
  let outbound: number;

  beforeEach(() => {
    service = new SharedService({} as any, {} as any, { set: () => undefined } as any);
    inbound = 0;
    outbound = 0;
    service.reloadInbound$.subscribe(() => inbound++);
    service.reloadOutbound$.subscribe(() => outbound++);
  });

  it('reloads only the inbound mappings for INBOUND', () => {
    service.reloadMappings(Direction.INBOUND);
    expect([inbound, outbound]).toEqual([1, 0]);
  });

  it('reloads only the outbound mappings for OUTBOUND', () => {
    service.reloadMappings(Direction.OUTBOUND);
    expect([inbound, outbound]).toEqual([0, 1]);
  });

  it('reloads both when no direction is given', () => {
    service.reloadMappings();
    expect([inbound, outbound]).toEqual([1, 1]);
  });

  it('exposes the signals as read-only observables', () => {
    expect((service.reloadInbound$ as any).next).toBeUndefined();
    expect((service.reloadOutbound$ as any).next).toBeUndefined();
  });
});
