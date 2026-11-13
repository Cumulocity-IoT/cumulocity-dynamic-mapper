import { MappingService } from './mapping.service';

/**
 * The backend provides `$isDuplicate(key, windowSeconds)` in the mapping filter. The editor evaluates the filter in the
 * browser, which has no message history, so it must know the function and report "no duplicate"; otherwise every
 * filter that uses it is marked invalid and cannot be saved.
 *
 * The service is created without its injected dependencies: these methods only need the jsonata library.
 */
describe('MappingService expression evaluation', () => {
  let service: MappingService;

  beforeEach(() => {
    service = Object.create(MappingService.prototype) as MappingService;
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    (service as unknown as { JSONATA: unknown }).JSONATA = require('jsonata');
  });

  it('evaluates a filter that uses $isDuplicate as "not a duplicate"', async () => {
    const result = await service.evaluateExpression(
      { id: 'd1', time: 't1' } as never,
      '$not($isDuplicate(id & "|" & time, 60))',
      true
    );
    expect(result as unknown).toBe(true);
  });

  it('rejects $isDuplicate outside the execution filter, as the backend does', async () => {
    await expectAsync(
      service.evaluateExpression({ id: 'd1', time: 't1' } as never, '$not($isDuplicate(id, 60))')
    ).toBeRejected();
  });

  it('validates a filter that combines $isDuplicate with another condition', async () => {
    const valid = await service.validateExpression(
      { id: 'd1', value: 20 } as never,
      'value > 10 and $not($isDuplicate(id, 60))',
      true
    );
    expect(valid).toBe(true);
  });

  it('still evaluates an ordinary expression', async () => {
    const result = await service.evaluateExpression({ value: 20 } as never, 'value > 10');
    expect(result as unknown).toBe(true);
  });
});
