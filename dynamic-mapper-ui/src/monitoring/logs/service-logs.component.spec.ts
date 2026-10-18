/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */
import { convertToParamMap } from '@angular/router';
import { Direction } from '../../shared';
import { ServiceLogsComponent } from './service-logs.component';
import { LogsUnavailableError, LogWindow } from './service-logs.model';

describe('ServiceLogsComponent mapping selection', () => {
  const window = (logs: string): LogWindow => ({ dateFrom: null, dateTo: null, logs, more: false, instance: '', instances: [], empty: false, omitted: 0 });

  function make(queryParams: Record<string, string> = {}, opts: { nativeUsable?: boolean; collectedEmpty?: boolean } = {}) {
    const loads: any[] = [];
    const infos: string[] = [];
    const collected = {
      kind: 'collected',
      init: async () => undefined,
      load: async (request: any) => {
        loads.push({ ...request, source: 'collected' });
        return { ...window(''), empty: !!opts.collectedEmpty };
      }
    };
    // The platform log is not available to this tenant: the page falls back to the collected log, like in production.
    const native = {
      kind: 'native',
      init: async () => {
        if (!opts.nativeUsable) throw new LogsUnavailableError('not the owner');
      },
      load: async (request: any) => {
        loads.push({ ...request, source: 'native' });
        return window('');
      }
    };
    const mappingService = {
      getMappings: async () => [
        { identifier: 'in1', name: 'Temperature', direction: Direction.INBOUND },
        { identifier: 'out1', name: 'Alarms', direction: Direction.OUTBOUND },
        { identifier: 'in2', name: 'Humidity', direction: Direction.INBOUND },
        { identifier: 'bad id', name: 'Broken', direction: Direction.INBOUND }
      ]
    };
    const component = new ServiceLogsComponent(native as any, collected as any, mappingService as any, { info: (m: string) => infos.push(m), danger: () => undefined } as any, {
      snapshot: { queryParamMap: convertToParamMap(queryParams) }
    } as any);
    component.source = collected as any;
    return { component, loads, infos };
  }

  async function withMappings() {
    const made = make();
    await (made.component as any).loadMappings();
    return made;
  }

  it('offers every mapping, sorted by label, and leaves out identifiers that cannot be a tag', async () => {
    const { component } = await withMappings();

    expect(component.mappingItems.map((i) => i.value)).toEqual(['out1', 'in2', 'in1']);
    expect(component.mappingItems[0]).toEqual({ label: 'Alarms (outbound)', value: 'out1' });
  });

  it('narrows the list to the inbound or the outbound mappings', async () => {
    const { component } = await withMappings();

    component.directionFilter = 'INBOUND';
    component.onDirectionChange();
    expect(component.mappingItems.map((i) => i.value)).toEqual(['in2', 'in1']);

    component.directionFilter = 'OUTBOUND';
    component.onDirectionChange();
    expect(component.mappingItems.map((i) => i.value)).toEqual(['out1']);

    component.directionFilter = 'ALL';
    component.onDirectionChange();
    expect(component.mappingItems.length).toBe(3);
  });

  it('drops a selected mapping that the narrowed list no longer offers, and shows all lines again', async () => {
    const { component, loads } = await withMappings();
    component.selectedMapping = 'out1';

    component.directionFilter = 'INBOUND';
    component.onDirectionChange();

    expect(component.selectedMapping).toBe('');
    expect(loads.length).toBe(1);
    expect(loads[0].mapping).toBeUndefined();
  });

  it('keeps a selected mapping that is still offered and does not reload', async () => {
    const { component, loads } = await withMappings();
    component.selectedMapping = 'in1';

    component.directionFilter = 'INBOUND';
    component.onDirectionChange();

    expect(component.selectedMapping).toBe('in1');
    expect(loads.length).toBe(0);
  });

  it('asks for the selected mapping when the window is loaded', async () => {
    const { component, loads } = await withMappings();
    component.selectedMapping = 'in1';

    await component.loadLatest();

    expect(loads[0].mapping).toBe('in1');
  });

  it('shows an empty window of a selected mapping instead of leaving the old one in place', async () => {
    const { component } = await withMappings();
    component.selectedMapping = 'in1';
    component.logsText = 'old unfiltered lines';

    await component.loadLatest();

    expect(component.logsText).toBe('');
    expect(component.noLinesForMapping).toBe(true);
  });

  it('does not claim "no lines for this mapping" when the load failed', async () => {
    const { component } = await withMappings();
    component.selectedMapping = 'in1';
    component.isLoading = false;

    expect(component.noLinesForMapping).toBe(true);
    component.error = 'boom';
    expect(component.noLinesForMapping).toBe(false);
  });

  it('works without a mapping list when it cannot be loaded', async () => {
    const { component } = make();
    (component as any).mappingService = { getMappings: async () => Promise.reject(new Error('no')) };

    await (component as any).loadMappings();

    expect(component.mappings).toEqual([]);
    expect(component.mappingItems).toEqual([]);
  });

  it('keeps the first row at 12 columns for either source, with and without mappings', async () => {
    const { component } = make();
    const cols = (cls: string) => Number(/col-md-(\d+)/.exec(cls)[1]);

    // no mappings: the date alone at the right edge
    expect(component.dateColumnClass).toBe('col-md-5 col-md-offset-7');
    // with mappings: mapping controls (7) + date (5), the same for the platform log (no instance selector)
    await (component as any).loadMappings();
    expect(7 + cols(component.dateColumnClass)).toBe(12);
    (component as any).source = { kind: 'native' };
    expect(7 + cols(component.dateColumnClass)).toBe(12);
  });

  it('selects a mapping from the select, and the clear button goes back to all lines', async () => {
    const { component, loads } = await withMappings();

    component.onMappingSelect({ label: 'Temperature (inbound)', value: 'in1' });
    expect(component.selectedMapping).toBe('in1');
    expect(loads[0].mapping).toBe('in1');

    component.onMappingDeselect();
    expect(component.selectedMapping).toBe('');
    expect(loads[1].mapping).toBeUndefined();

    component.onMappingDeselect(); // nothing selected: nothing to reload
    expect(loads.length).toBe(2);
  });

  it('keeps the same items array until the list really changes', async () => {
    const { component } = await withMappings();
    const items = component.mappingItems;

    component.onDirectionChange(); // still ALL: rebuilt, but only on this call, never by change detection
    expect(component.mappingItems).not.toBe(undefined);
    expect(component.mappingItems).toEqual(items);
  });

  it('opens with the mapping of the link preselected, and the very first load is already filtered', async () => {
    const { component, loads } = make({ mapping: 'in1' });

    await component.ngOnInit();

    expect(component.selectedMapping).toBe('in1');
    // the first load is the probe of the collected log, the page itself starts filtered
    expect(loads.slice(1).every((l) => l.mapping === 'in1')).toBe(true);
    expect(loads.length).toBeGreaterThan(1);
  });

  it('ignores a mapping from the link that does not exist, tells the user, and shows all lines', async () => {
    const { component, loads, infos } = make({ mapping: 'gone' });

    await component.ngOnInit();

    expect(component.selectedMapping).toBe('');
    expect(loads.every((l) => l.mapping === undefined)).toBe(true);
    expect(infos.length).toBe(1);
  });

  it('ignores a value from the link that cannot be an identifier', async () => {
    const { component, loads } = make({ mapping: 'x&type=other' });

    await component.ngOnInit();

    expect(component.selectedMapping).toBe('');
    expect(loads.every((l) => l.mapping === undefined)).toBe(true);
  });

  it('opens unfiltered without a link parameter', async () => {
    const { component, loads, infos } = make();

    await component.ngOnInit();

    expect(component.selectedMapping).toBe('');
    expect(loads.every((l) => l.mapping === undefined)).toBe(true);
    expect(infos.length).toBe(0);
  });

  describe('source choice (owner tenant with a collected log)', () => {
    const both = { nativeUsable: true };

    it('offers the switch and starts on the platform log when no mapping is asked for', async () => {
      const { component } = make({}, both);
      await component.ngOnInit();

      expect(component.canChooseSource).toBe(true);
      expect(component.source.kind).toBe('native');
    });

    it('offers no switch when the collected log holds nothing, and filters the platform log client-side', async () => {
      const { component } = make({}, { nativeUsable: true, collectedEmpty: true });
      await component.ngOnInit();
      expect(component.canChooseSource).toBe(false);

      component.onMappingSelect({ label: 'x', value: 'in1' });
      expect(component.source.kind).toBe('native');
      expect(component.mappingSourceNote).toBeUndefined();
    });

    it('offers no switch to a tenant that cannot read the platform log', async () => {
      const { component } = make({}, {});
      await component.ngOnInit();

      expect(component.canChooseSource).toBe(false);
      expect(component.source.kind).toBe('collected');
    });

    it('selecting a mapping switches to the collected log with a note, clearing it returns to the platform log', async () => {
      const { component, loads } = make({}, both);
      await component.ngOnInit();
      loads.length = 0;

      component.onMappingSelect({ label: 'x', value: 'in1' });
      await new Promise((r) => setTimeout(r));
      expect(component.source.kind).toBe('collected');
      expect(component.mappingSourceNote).toContain('collected log');
      expect(loads).toEqual([jasmine.objectContaining({ source: 'collected', mapping: 'in1' })]);

      loads.length = 0;
      component.onMappingDeselect();
      await new Promise((r) => setTimeout(r));
      expect(component.source.kind).toBe('native');
      expect(component.mappingSourceNote).toBeUndefined();
      expect(loads).toEqual([jasmine.objectContaining({ source: 'native', mapping: undefined })]);
    });

    it('a mapping from the link starts on the collected log, and clearing it returns to the platform log', async () => {
      const { component } = make({ mapping: 'in1' }, both);
      await component.ngOnInit();
      expect(component.source.kind).toBe('collected');
      expect(component.canChooseSource).toBe(true);

      component.onMappingDeselect();
      await new Promise((r) => setTimeout(r));
      expect(component.source.kind).toBe('native');
    });

    it('switching to the platform log by hand drops the mapping filter and shows all lines', async () => {
      const { component, loads } = make({ mapping: 'in1' }, both);
      await component.ngOnInit();
      loads.length = 0;

      await component.onSourceChange(false);

      expect(component.source.kind).toBe('native');
      expect(component.selectedMapping).toBe('');
      expect(loads).toEqual([jasmine.objectContaining({ source: 'native', mapping: undefined })]);
    });

    it('an explicit choice of the source is not undone when the mapping is cleared', async () => {
      const { component } = make({}, both);
      await component.ngOnInit();
      component.onMappingSelect({ label: 'x', value: 'in1' });
      await new Promise((r) => setTimeout(r));

      await component.onSourceChange(true);
      component.onMappingDeselect();
      await new Promise((r) => setTimeout(r));

      expect(component.source.kind).toBe('collected');
    });
  });
});
