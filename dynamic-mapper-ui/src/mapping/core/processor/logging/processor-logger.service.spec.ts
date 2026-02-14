/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { TestBed } from '@angular/core/testing';
import { ProcessorLoggerService, LogLevel } from './processor-logger.service';
import { createMockProcessingContext } from '../__tests__/test-fixtures';
import { ProcessingContext } from '../processor.model';

describe('ProcessorLoggerService', () => {
  let service: ProcessorLoggerService;
  let context: ProcessingContext;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(ProcessorLoggerService);
    context = createMockProcessingContext();

    // Spy on console methods
    spyOn(console, 'debug');
    spyOn(console, 'info');
    spyOn(console, 'warn');
    spyOn(console, 'error');
  });

  describe('configure', () => {
    it('should update configuration', () => {
      service.configure({ minLevel: LogLevel.ERROR });

      service.info('Test message', {}, context);

      // INFO should not be logged when minLevel is ERROR
      expect(console.info).not.toHaveBeenCalled();
    });

    it('should disable console output', () => {
      service.configure({ outputToConsole: false });

      service.info('Test message', {}, context);

      expect(console.info).not.toHaveBeenCalled();
    });

    it('should disable context logging', () => {
      service.configure({ addToContext: false });

      service.info('Test message', {}, context);

      expect(context.logs?.length).toBe(0);
    });
  });

  describe('debug', () => {
    it('should log debug messages', () => {
      service.configure({ minLevel: LogLevel.DEBUG });

      service.debug('Debug message', { key: 'value' }, context);

      expect(console.debug).toHaveBeenCalled();
      expect(context.logs?.length).toBe(1);
      expect(context.logs![0]).toEqual(jasmine.objectContaining({
        level: LogLevel.DEBUG,
        message: 'Debug message',
        context: { key: 'value' }
      }));
    });

    it('should not log debug when minLevel is INFO', () => {
      service.configure({ minLevel: LogLevel.INFO });

      service.debug('Debug message', {}, context);

      expect(console.debug).not.toHaveBeenCalled();
    });
  });

  describe('info', () => {
    it('should log info messages', () => {
      service.info('Info message', { mappingId: '123' }, context);

      expect(console.info).toHaveBeenCalled();
      expect(context.logs?.length).toBe(1);
      expect(context.logs![0]).toEqual(jasmine.objectContaining({
        level: LogLevel.INFO,
        message: 'Info message',
        context: { mappingId: '123' }
      }));
    });

    it('should include timestamp', () => {
      service.info('Test', {}, context);

      expect(context.logs![0].timestamp).toBeDefined();
      expect(typeof context.logs![0].timestamp).toBe('string');
    });
  });

  describe('warn', () => {
    it('should log warnings', () => {
      service.warn('Warning message', { path: '$.test' }, context);

      expect(console.warn).toHaveBeenCalled();
      expect(context.logs?.length).toBe(1);
      expect(context.logs![0]).toEqual(jasmine.objectContaining({
        level: LogLevel.WARN,
        message: 'Warning message',
        context: { path: '$.test' }
      }));
    });
  });

  describe('error', () => {
    it('should log errors with error objects', () => {
      const error = new Error('Test error');

      service.error('Error occurred', error, { operation: 'test' }, context);

      expect(console.error).toHaveBeenCalled();
      expect(context.logs?.length).toBe(1);
      expect(context.logs![0]).toEqual(jasmine.objectContaining({
        level: LogLevel.ERROR,
        message: 'Error occurred',
        context: { operation: 'test' },
        error: {
          message: 'Test error',
          stack: jasmine.any(String)
        }
      }));
    });

    it('should log errors without error objects', () => {
      service.error('Error message', undefined, { code: 'ERR_001' }, context);

      expect(console.error).toHaveBeenCalled();
      expect(context.logs![0]).toEqual(jasmine.objectContaining({
        level: LogLevel.ERROR,
        message: 'Error message',
        context: { code: 'ERR_001' }
      }));
    });
  });

  describe('log level filtering', () => {
    it('should filter logs based on minLevel', () => {
      service.configure({ minLevel: LogLevel.WARN });

      service.debug('Debug', {}, context);
      service.info('Info', {}, context);
      service.warn('Warn', {}, context);
      service.error('Error', undefined, {}, context);

      expect(console.debug).not.toHaveBeenCalled();
      expect(console.info).not.toHaveBeenCalled();
      expect(console.warn).toHaveBeenCalled();
      expect(console.error).toHaveBeenCalled();
      expect(context.logs?.length).toBe(2);
    });
  });

  describe('createChildLogger', () => {
    it('should create child logger with base context', () => {
      const childLogger = service.createChildLogger({ mappingId: 'test-123' });

      childLogger.info('Child log', { additional: 'data' }, context);

      expect(context.logs![0]).toEqual(jasmine.objectContaining({
        message: 'Child log',
        context: {
          mappingId: 'test-123',
          additional: 'data'
        }
      }));
    });

    it('should merge child context with log context', () => {
      const childLogger = service.createChildLogger({
        mappingId: '123',
        topic: 'test/topic'
      });

      childLogger.debug('Test', { step: 1 }, context);

      expect(context.logs![0].context).toEqual({
        mappingId: '123',
        topic: 'test/topic',
        step: 1
      });
    });

    it('should support all log levels', () => {
      service.configure({ minLevel: LogLevel.DEBUG });
      const childLogger = service.createChildLogger({ base: 'context' });

      childLogger.debug('Debug', {}, context);
      childLogger.info('Info', {}, context);
      childLogger.warn('Warn', {}, context);
      childLogger.error('Error', new Error('test'), {}, context);

      expect(context.logs?.length).toBe(4);
      expect(context.logs!.every(log => log.context.base === 'context')).toBe(true);
    });
  });

  describe('getLogsFromContext', () => {
    beforeEach(() => {
      service.configure({ minLevel: LogLevel.DEBUG });
    });

    it('should return all logs', () => {
      service.debug('Debug', {}, context);
      service.info('Info', {}, context);
      service.warn('Warn', {}, context);

      const logs = service.getLogsFromContext(context);

      expect(logs.length).toBe(3);
    });

    it('should filter logs by minLevel', () => {
      service.debug('Debug', {}, context);
      service.info('Info', {}, context);
      service.warn('Warn', {}, context);
      service.error('Error', undefined, {}, context);

      const logs = service.getLogsFromContext(context, LogLevel.WARN);

      expect(logs.length).toBe(2);
      expect(logs[0].level).toBe(LogLevel.WARN);
      expect(logs[1].level).toBe(LogLevel.ERROR);
    });

    it('should return empty array for context without logs', () => {
      const emptyContext = createMockProcessingContext();
      emptyContext.logs = undefined;

      const logs = service.getLogsFromContext(emptyContext);

      expect(logs).toEqual([]);
    });
  });

  describe('clearLogs', () => {
    it('should clear all logs from context', () => {
      service.info('Test 1', {}, context);
      service.info('Test 2', {}, context);

      expect(context.logs?.length).toBe(2);

      service.clearLogs(context);

      expect(context.logs?.length).toBe(0);
    });

    it('should handle context without logs array', () => {
      const emptyContext = createMockProcessingContext();
      emptyContext.logs = undefined;

      expect(() => service.clearLogs(emptyContext)).not.toThrow();
    });
  });

  describe('console output formatting', () => {
    it('should format messages with timestamp, level, and message', () => {
      service.info('Test message', {}, context);

      const callArgs = (console.info as jasmine.Spy).calls.mostRecent().args;
      expect(callArgs[0]).toContain('[INFO]');
      expect(callArgs[0]).toContain('[Processor]');
      expect(callArgs[0]).toContain('Test message');
    });

    it('should use custom formatter when provided', () => {
      service.configure({
        formatter: (entry) => `CUSTOM: ${entry.message}`
      });

      service.info('Test', {}, context);

      const callArgs = (console.info as jasmine.Spy).calls.mostRecent().args;
      expect(callArgs[0]).toBe('CUSTOM: Test');
    });
  });

  describe('without ProcessingContext', () => {
    it('should log to console without context', () => {
      service.info('Standalone log', { key: 'value' });

      expect(console.info).toHaveBeenCalled();
    });

    it('should not crash when context is undefined', () => {
      expect(() => {
        service.debug('Test', {}, undefined);
        service.info('Test', {}, undefined);
        service.warn('Test', {}, undefined);
        service.error('Test', undefined, {}, undefined);
      }).not.toThrow();
    });
  });
});
