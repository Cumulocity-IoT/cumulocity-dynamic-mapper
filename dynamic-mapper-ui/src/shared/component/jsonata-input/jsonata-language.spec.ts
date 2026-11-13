import {
  DYNAMIC_MAPPER_FUNCTIONS,
  JSONATA_FUNCTIONS,
  enableMapperFunctions,
  findParseError,
  functionsFor,
  pathSuggestions,
  quoteKey,
  toMarker
} from './jsonata-language';

const jsonata = require('jsonata');
const parse = (expression: string) => jsonata(expression);

describe('jsonata-language', () => {
  describe('findParseError', () => {
    it('accepts valid and empty expressions', () => {
      expect(findParseError(parse, '$join([$substring(txt,5), id])')).toBeUndefined();
      expect(findParseError(parse, '')).toBeUndefined();
      expect(findParseError(parse, '   ')).toBeUndefined();
    });

    it('returns the parser error with a position', () => {
      const error = findParseError(parse, '$join([a, b)');
      expect(error?.message).toBeTruthy();
      expect(typeof error?.position).toBe('number');
    });
  });

  describe('toMarker', () => {
    it('covers the offending token on line 1', () => {
      const expression = 'a + * b';
      const error = findParseError(parse, expression)!;
      const marker = toMarker(error, expression, 8);
      expect(marker.startLineNumber).toBe(1);
      expect(marker.endLineNumber).toBe(1);
      expect(marker.startColumn).toBeGreaterThanOrEqual(1);
      expect(marker.endColumn).toBeGreaterThan(marker.startColumn);
    });

    it('stays inside the text when the error is at the end', () => {
      const marker = toMarker({ message: 'x', position: 99, token: '(end)' }, 'abc', 8);
      expect(marker.startColumn).toBe(3);
      expect(marker.endColumn).toBe(4);
    });
  });

  describe('pathSuggestions', () => {
    const template = { Account: { Product: [{ Price: 1, Name: 'a' }, { Price: 2, Qty: 3 }], Id: 1 }, time: 'x' };

    it('offers root keys without a prefix', () => {
      expect(pathSuggestions(template, '')).toEqual(['Account', 'time']);
      expect(pathSuggestions(template, 'Acc')).toEqual(['Account', 'time']);
    });

    it('follows a dotted path and looks through arrays', () => {
      expect(pathSuggestions(template, 'Account.')).toEqual(['Product', 'Id']);
      expect(pathSuggestions(template, 'Account.Product.')).toEqual(['Price', 'Name', 'Qty']);
    });

    it('works inside a function call', () => {
      expect(pathSuggestions(template, '$sum(Account.Product.Pr')).toEqual(['Price', 'Name', 'Qty']);
    });

    it('returns nothing for an unknown path or a missing template', () => {
      expect(pathSuggestions(template, 'nope.')).toEqual([]);
      expect(pathSuggestions(undefined, 'a.')).toEqual([]);
    });
  });

  it('quotes keys that are not valid names', () => {
    expect(quoteKey('abc_1')).toBe('abc_1');
    expect(quoteKey('c8y_Temperature-1')).toBe('`c8y_Temperature-1`');
  });

  it('lists every built-in function once, and each exists in the installed jsonata', async () => {
    const names = JSONATA_FUNCTIONS.map((f) => f.name);
    expect(new Set(names).size).toBe(names.length);
    for (const name of names) {
      // An unknown `$name` evaluates to undefined; a built-in evaluates to the function.
      const value = await parse(`$${name}`).evaluate({});
      expect(value).withContext(`$${name}`).toBeDefined();
    }
  });

  it('offers the mapper-specific functions without clashing with a built-in', async () => {
    const builtIn = new Set(JSONATA_FUNCTIONS.map((f) => f.name));
    for (const fn of DYNAMIC_MAPPER_FUNCTIONS) {
      expect(builtIn.has(fn.name)).withContext(fn.name).toBeFalse();
      // Not part of the installed jsonata: it exists only because the backend and the editor register it.
      expect(await parse(`$${fn.name}`).evaluate({})).withContext(fn.name).toBeUndefined();
    }
    expect(DYNAMIC_MAPPER_FUNCTIONS.map((f) => f.name)).toContain('isDuplicate');
  });

  it('offers the mapper-specific functions only in editors that opted in', () => {
    const filterModel = {} as never;
    const otherModel = {} as never;
    enableMapperFunctions(filterModel);

    const names = (model: never) => functionsFor(model).map((f) => f.name);
    expect(names(filterModel)).toContain('isDuplicate');
    expect(names(filterModel)).toContain('string');
    expect(names(otherModel)).not.toContain('isDuplicate');
    expect(names(otherModel)).toContain('string');
  });
});
