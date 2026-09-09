import { Router } from '@angular/router';
import { VersionsTabFactory } from './versions-tab.factory';
import { NODE2 } from '../shared/mapping/util';

/**
 * `VersionsTabFactory` implements c8y's `TabFactory` interface: `get()` returns the
 * Inbound/Outbound sub-tabs for the "Versions" monitoring page, but only when the
 * current URL is actually under `.../monitoring/versions` — otherwise it must return an
 * empty tab list (it is registered globally, so it also gets asked on unrelated pages).
 * Plain unit test against a stubbed Router; no TestBed/component involved.
 */
describe('VersionsTabFactory', () => {
  function makeFactory(url: string): VersionsTabFactory {
    return new VersionsTabFactory({ url } as Router);
  }

  it('returns no tabs when the current URL is not the versions monitoring page', async () => {
    const factory = makeFactory('c8y-pkg-dynamic-mapper/node2/monitoring/mappings');
    const tabs = await factory.get();
    expect(tabs).toEqual([]);
  });

  it('returns Inbound and Outbound tabs when on the versions monitoring page', async () => {
    const factory = makeFactory(`c8y-pkg-dynamic-mapper/${NODE2}/monitoring/versions/inbound`);
    const tabs = await factory.get();

    expect(tabs.length).toBe(2);

    const inbound = tabs.find(t => t.label === 'Inbound');
    expect(inbound).toBeTruthy();
    expect(inbound?.path).toBe(`c8y-pkg-dynamic-mapper/${NODE2}/monitoring/versions/inbound`);
    expect(inbound?.icon).toBe('swipe-right');
    expect(inbound?.orientation).toBe('horizontal');
    expect(inbound?.priority).toBe(930);

    const outbound = tabs.find(t => t.label === 'Outbound');
    expect(outbound).toBeTruthy();
    expect(outbound?.path).toBe(`c8y-pkg-dynamic-mapper/${NODE2}/monitoring/versions/outbound`);
    expect(outbound?.icon).toBe('swipe-left');
    expect(outbound?.orientation).toBe('horizontal');
    expect(outbound?.priority).toBe(920);
  });

  it('ranks the Inbound tab ahead of the Outbound tab by priority', async () => {
    const factory = makeFactory(`c8y-pkg-dynamic-mapper/${NODE2}/monitoring/versions/outbound`);
    const [first, second] = await factory.get();
    expect(first.priority).toBeGreaterThan(second.priority);
  });
});
