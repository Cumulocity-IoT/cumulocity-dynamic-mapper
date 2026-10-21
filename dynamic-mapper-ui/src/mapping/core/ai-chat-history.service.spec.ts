import '../../shared';
import {
  AiChatHistoryService,
  codeTargetKey,
  generationInputsKey,
  isStoredResultReusable,
  StoredAiSession,
  substitutionsTargetKey
} from './ai-chat-history.service';

describe('AiChatHistoryService', () => {
  let service: AiChatHistoryService;
  const session = (overrides: Partial<StoredAiSession> = {}): StoredAiSession => ({
    inputsKey: 'in',
    targetKeyAtOpen: 'old',
    extractedKey: 'new',
    history: {} as any,
    generatedCode: 'code',
    substitutions: [],
    validationIssues: [],
    valid: true,
    ...overrides
  });

  beforeEach(() => (service = new AiChatHistoryService()));

  it('returns a stored session for the same identifier', () => {
    service.set('m1', session());
    expect(service.get('m1')?.generatedCode).toBe('code');
  });

  it('keeps sessions of different mappings apart', () => {
    service.set('m1', session());
    expect(service.get('m2')).toBeUndefined();
  });

  it('ignores a missing identifier', () => {
    service.set(undefined, session());
    expect(service.get(undefined)).toBeUndefined();
  });

  it('delete removes the session', () => {
    service.set('m1', session());
    service.delete('m1');
    expect(service.get('m1')).toBeUndefined();
  });
});

describe('generationInputsKey', () => {
  const base = { sourceTemplate: { a: 1 }, targetTemplate: {}, direction: 'INBOUND', targetAPI: 'MEASUREMENT' };

  it('ignores fields that change on every save', () => {
    expect(generationInputsKey({ ...base, lastUpdate: 1, version: 2, draftDirty: true })).toBe(
      generationInputsKey({ ...base, lastUpdate: 9, version: 3, draftDirty: false })
    );
  });

  it('ignores the code and substitutions, which are the output', () => {
    expect(generationInputsKey({ ...base, code: 'a', substitutions: [] })).toBe(
      generationInputsKey({ ...base, code: 'b', substitutions: [{}] })
    );
  });

  it('changes when a template or the target API changes', () => {
    expect(generationInputsKey({ ...base, sourceTemplate: { a: 2 } })).not.toBe(generationInputsKey(base));
    expect(generationInputsKey({ ...base, targetAPI: 'ALARM' })).not.toBe(generationInputsKey(base));
  });
});

describe('target keys', () => {
  it('treat code as equal across line endings and surrounding whitespace', () => {
    expect(codeTargetKey('function onMessage() {}\r\n')).toBe(codeTargetKey('  function onMessage() {}'));
  });

  it('tell different code apart', () => {
    expect(codeTargetKey('function onMessage() { return 1; }')).not.toBe(codeTargetKey('function onMessage() { return 2; }'));
  });

  it('apply the drawer defaults when comparing substitutions', () => {
    expect(substitutionsTargetKey([{ pathSource: 'a', pathTarget: 'b' }])).toBe(
      substitutionsTargetKey([{ pathSource: 'a', pathTarget: 'b', expandArray: false, repairStrategy: 'DEFAULT' as any }])
    );
  });
});

describe('isStoredResultReusable', () => {
  const stored = (o: Partial<StoredAiSession> = {}): StoredAiSession => ({
    inputsKey: 'in',
    targetKeyAtOpen: 'old',
    extractedKey: 'new',
    history: {} as any,
    generatedCode: '',
    substitutions: [],
    validationIssues: [],
    valid: true,
    ...o
  });

  it('is reusable when the drawer was cancelled and nothing changed', () => {
    expect(isStoredResultReusable(stored(), 'in', 'old')).toBeTrue();
  });

  it('is reusable right after Save, when the mapping now holds the result', () => {
    expect(isStoredResultReusable(stored(), 'in', 'new')).toBeTrue();
  });

  it('is not reusable once the user edited the code or substitutions by hand', () => {
    expect(isStoredResultReusable(stored(), 'in', 'edited by hand')).toBeFalse();
  });

  it('is not reusable when the conversation was about different inputs', () => {
    expect(isStoredResultReusable(stored(), 'other inputs', 'old')).toBeFalse();
  });

  it('is not reusable when no valid result had been extracted', () => {
    expect(isStoredResultReusable(stored({ valid: false }), 'in', 'old')).toBeFalse();
  });
});
