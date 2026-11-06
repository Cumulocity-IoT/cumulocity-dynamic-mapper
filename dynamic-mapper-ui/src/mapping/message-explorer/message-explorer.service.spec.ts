import { isRawPayload } from './message-explorer.service';

describe('isRawPayload', () => {
  it('is true for binary, XML and plain text, which no JSON mapping can parse', () => {
    expect(isRawPayload({ binary: true, payloadType: 'BINARY' })).toBe(true);
    expect(isRawPayload({ binary: false, payloadType: 'XML' })).toBe(true);
    expect(isRawPayload({ binary: false, payloadType: 'TEXT' })).toBe(true);
  });

  it('is false for JSON and empty payloads', () => {
    expect(isRawPayload({ binary: false, payloadType: 'JSON' })).toBe(false);
    expect(isRawPayload({ binary: false, payloadType: 'EMPTY' })).toBe(false);
  });

  it('falls back to the binary flag when the backend sends no payload type', () => {
    expect(isRawPayload({ binary: true })).toBe(true);
    expect(isRawPayload({ binary: false })).toBe(false);
  });
});
