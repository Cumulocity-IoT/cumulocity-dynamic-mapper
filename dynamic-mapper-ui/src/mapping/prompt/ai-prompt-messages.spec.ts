import { Direction, Mapping, TransformationType } from '../../shared';
import { buildGenerateMessage, buildReviewMessage } from './ai-prompt-messages';

describe('buildGenerateMessage (inbound Smart Function)', () => {
  function makeMapping(overrides: Partial<Mapping> = {}): Mapping {
    return {
      direction: Direction.INBOUND,
      transformationType: TransformationType.SMART_FUNCTION,
      targetAPI: 'EVENT',
      sourceTemplate: '{"temperature":23.6,"unit":"C","source":"961837264"}',
      targetTemplate: '{}',
      ...overrides
    } as Mapping;
  }

  it('shows the source message and says its structure is arbitrary', () => {
    const message = buildGenerateMessage(makeMapping());

    expect(message).toContain('"temperature": 23.6');
    expect(message).toContain('"source": "961837264"');
    expect(message).toContain('arbitrary');
    expect(message).toContain('do not assume its field names match');
    expect(message).toContain('do not invent data');
  });

  it('sends no canned target example, and still tells the agent not to ask', () => {
    const message = buildGenerateMessage(makeMapping());

    expect(message).not.toContain('A typical EVENT object');
    expect(message).not.toContain('c8y_TestEvent');
    expect(message).toContain('do not ask the user for a target format');
  });

  it('treats every source alike, whether it came from Message Explorer or from a sample', () => {
    const explorer = buildGenerateMessage(makeMapping({ sourceTemplate: '{"a":1}' }));
    const sample = buildGenerateMessage(makeMapping({ sourceTemplate: '{"externalId":"x","eventType":"TestEvent"}' }));

    expect(explorer).not.toContain('captured from the broker');
    expect(sample).not.toContain('captured from the broker');
  });

  it('truncates a very large source', () => {
    const message = buildGenerateMessage(makeMapping({ sourceTemplate: JSON.stringify({ blob: 'x'.repeat(5000) }) }));

    expect(message).toContain('truncated');
    expect(message.length).toBeLessThan(4000);
  });

  it('asks for the target API when none is set', () => {
    expect(buildGenerateMessage(makeMapping({ targetAPI: undefined }))).toContain('targetAPI is not set');
  });

  describe('outbound', () => {
    function makeOutbound(overrides: Partial<Mapping> = {}): Mapping {
      return makeMapping({
        direction: Direction.OUTBOUND,
        sourceTemplate: '{"id":"4711","type":"c8y_TestEvent","text":"door opened","time":"2026-11-20T12:00:00.000Z"}',
        ...overrides
      });
    }

    it('labels targetAPI as the source API, not the output', () => {
      const message = buildGenerateMessage(makeOutbound());

      expect(message).toContain('(source: Cumulocity EVENT)');
      expect(message).not.toContain('target: EVENT');
    });

    it('shows the Cumulocity source object', () => {
      const message = buildGenerateMessage(makeOutbound());

      expect(message).toContain('"text": "door opened"');
      expect(message).toContain('Cumulocity EVENT object below');
    });

    it('says the receiver format is unknown and asks the agent to state its choice, without a canned example', () => {
      const message = buildGenerateMessage(makeOutbound());

      expect(message).toContain("receiver's format is not known");
      expect(message).toContain('state the structure you chose');
      expect(message).toContain('do not ask the user for a target format');
      expect(message).not.toContain('infer reasonable field names');
      expect(message).not.toContain('A typical');
    });

    it('truncates a very large source', () => {
      const message = buildGenerateMessage(makeOutbound({ sourceTemplate: JSON.stringify({ blob: 'x'.repeat(5000) }) }));

      expect(message).toContain('truncated');
    });
  });

  describe('review message', () => {
    it('labels targetAPI as the source API for outbound and as the target for inbound', () => {
      const base = { transformationType: TransformationType.SMART_FUNCTION, targetAPI: 'EVENT' } as Partial<Mapping>;

      expect(buildReviewMessage({ ...base, direction: Direction.OUTBOUND } as Mapping)).toContain('(source: Cumulocity EVENT)');
      expect(buildReviewMessage({ ...base, direction: Direction.INBOUND } as Mapping)).toContain('(target: EVENT)');
    });
  });
});
