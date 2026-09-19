import { Direction } from './mapping.model';
import { createCompletionProviderFlowFunction } from './stepper.model';

/**
 * The completion and hover providers for the Smart Function editors are pure text-in/items-out
 * logic, so they can be driven directly with a stub Monaco and a stub text model — no editor, no
 * DOM. These tests pin the behaviours that were previously broken: completing on a *variable*
 * (`context.`), not leaking globals after a dot, and resolving hovers against the receiver.
 */
describe('createCompletionProviderFlowFunction', () => {
  let completion: any;
  let hover: any;
  let disposed: number;

  const monacoStub = {
    languages: {
      CompletionItemKind: { Field: 1, Method: 2, Class: 3, Enum: 4, EnumMember: 5, Function: 6, Variable: 7, Value: 8, Constructor: 9 },
      CompletionItemTag: { Deprecated: 1 },
      CompletionItemInsertTextRule: { InsertAsSnippet: 4 },
      registerCompletionItemProvider: (_lang: string, provider: any) => { completion = provider; return { dispose: () => disposed++ }; },
      registerHoverProvider: (_lang: string, provider: any) => { hover = provider; return { dispose: () => disposed++ }; }
    }
  };

  /** Stub model exposing just what the providers read: the line up to the cursor, plus words. */
  function modelFor(line: string) {
    return {
      getValueInRange: ({ startColumn, endColumn }: any) => line.slice(startColumn - 1, endColumn - 1),
      getWordUntilPosition: () => {
        const m = line.match(/(\w*)$/);
        const w = m ? m[1] : '';
        return { word: w, startColumn: line.length - w.length + 1, endColumn: line.length + 1 };
      },
      getWordAtPosition: () => {
        const m = line.match(/(\w+)$/);
        if (!m) return null;
        return { word: m[1], startColumn: line.length - m[1].length + 1, endColumn: line.length + 1 };
      }
    };
  }

  const complete = (line: string) =>
    completion.provideCompletionItems(modelFor(line), { lineNumber: 1, column: line.length + 1 }, {}, {}).suggestions;
  const labelsOf = (items: any[]) => items.map(i => (typeof i.label === 'string' ? i.label : i.label.label));
  const hoverAt = (line: string) => hover.provideHover(modelFor(line), { lineNumber: 1, column: line.length + 1 });

  beforeEach(() => {
    disposed = 0;
    createCompletionProviderFlowFunction(monacoStub, Direction.INBOUND);
  });

  it('completes members of a known variable, not just of a class name', () => {
    const labels = labelsOf(complete('  context.'));
    expect(labels.some(l => l.startsWith('getExternalId('))).toBeTrue();
    expect(labels.some(l => l.startsWith('getManagedObjectByExternalId('))).toBeTrue();
  });

  it('keeps completing while the member name is being typed', () => {
    const labels = labelsOf(complete('  context.getE'));
    expect(labels.some(l => l.startsWith('getExternalId('))).toBeTrue();
  });

  it('offers nothing — rather than every global — after an unknown receiver', () => {
    expect(complete('  someLocalThing.')).toEqual([]);
  });

  it('still offers globals when not completing a member', () => {
    const labels = labelsOf(complete('  const x = Cum'));
    expect(labels).toContain('CumulocityObject');
  });

  it('suggests only constructors after `new`', () => {
    const items = complete('  const x = new ');
    expect(items.length).toBeGreaterThan(0);
    expect(items.every(i => i.kind === monacoStub.languages.CompletionItemKind.Constructor)).toBeTrue();
  });

  it('inserts method arguments as named snippet placeholders', () => {
    const setState = complete('  context.').find((i: any) => labelsOf([i])[0].startsWith('setState('));
    expect(setState.insertText).toBe('setState(${1:key}, ${2:value})');
  });

  it('resolves a hovered member against its receiver', () => {
    const contents = hoverAt('  const t = msg.payload').contents[0].value;
    expect(contents).toContain('DynamicMapperDeviceMessage.payload');
  });

  it('says nothing for an unknown member of a known receiver', () => {
    expect(hoverAt('  context.nonsense')).toBeNull();
  });

  it('documents onMessage for the direction it was registered with', () => {
    expect(hoverAt('function onMessage').contents[0].value).toContain('Inbound Smart Function');
  });

  it('disposes both providers', () => {
    const d = createCompletionProviderFlowFunction(monacoStub, Direction.INBOUND);
    disposed = 0;
    d.dispose();
    expect(disposed).toBe(2);
  });
});
