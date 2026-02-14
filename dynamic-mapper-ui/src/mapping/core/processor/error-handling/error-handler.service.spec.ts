/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { TestBed } from '@angular/core/testing';
import { ErrorHandlerService } from './error-handler.service';
import { ProcessorError, ProcessorErrorCode } from './processor-error';
import { ProcessingContext } from '../processor.model';
import { createMockProcessingContext } from '../__tests__/test-fixtures';

describe('ErrorHandlerService', () => {
  let service: ErrorHandlerService;
  let context: ProcessingContext;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(ErrorHandlerService);
    context = createMockProcessingContext();

    // Suppress console output during tests
    spyOn(console, 'warn');
    spyOn(console, 'error');
  });

  describe('configure', () => {
    it('should update configuration', () => {
      service.configure({ logToConsole: false });

      const error = ProcessorError.deviceNotFound('device001');
      service.handle(error, context);

      // Console should not be called
      expect(console.warn).not.toHaveBeenCalled();
    });

    it('should merge with existing configuration', () => {
      service.configure({ logToConsole: false });
      service.configure({ includeStackTrace: false });

      const error = ProcessorError.deviceNotFound('device001');
      service.handle(error, context);

      expect(console.warn).not.toHaveBeenCalled();
    });
  });

  describe('handle', () => {
    it('should add recoverable error to context.errors', () => {
      const error = ProcessorError.deviceNotFound('device001');

      service.handle(error, context);

      expect(context.errors.length).toBe(1);
      expect(context.errors[0]).toContain('[DEVICE_NOT_FOUND]');
      expect(context.errors[0]).toContain('Device not found: device001');
    });

    it('should add non-recoverable error to context.errors', () => {
      const error = ProcessorError.deviceCreationFailed('device001', 'Network error');

      expect(() => service.handle(error, context)).toThrow();

      expect(context.errors.length).toBe(1);
      expect(context.errors[0]).toContain('[DEVICE_CREATION_FAILED]');
    });

    it('should log recoverable errors as warnings', () => {
      const error = ProcessorError.deviceNotFound('device001');

      service.handle(error, context);

      expect(console.warn).toHaveBeenCalledWith(
        '[ProcessorError - Recoverable]',
        jasmine.objectContaining({
          code: ProcessorErrorCode.DEVICE_NOT_FOUND,
          recoverable: true
        })
      );
    });

    it('should log non-recoverable errors as errors', () => {
      const error = ProcessorError.payloadParsingFailed('Invalid JSON');

      expect(() => service.handle(error, context)).toThrow();

      expect(console.error).toHaveBeenCalledWith(
        '[ProcessorError - Non-Recoverable]',
        jasmine.objectContaining({
          code: ProcessorErrorCode.PAYLOAD_PARSING_FAILED,
          recoverable: false
        })
      );
    });

    it('should throw non-recoverable errors by default', () => {
      const error = ProcessorError.codeExecutionFailed('Syntax error');

      expect(() => service.handle(error, context)).toThrow(ProcessorError);
      expect(() => service.handle(error, context)).toThrowError('Code execution failed: Syntax error');
    });

    it('should not throw when throwOnNonRecoverable is false', () => {
      service.configure({ throwOnNonRecoverable: false });
      const error = ProcessorError.codeExecutionFailed('Syntax error');

      expect(() => service.handle(error, context)).not.toThrow();
      expect(context.errors.length).toBe(1);
    });

    it('should add error details to logs', () => {
      const error = ProcessorError.deviceNotFound('device001', { topic: 'test/topic' });

      service.handle(error, context);

      expect(context.logs).toBeDefined();
      expect(context.logs!.length).toBe(1);
      expect(context.logs![0]).toEqual(jasmine.objectContaining({
        level: 'error',
        code: ProcessorErrorCode.DEVICE_NOT_FOUND,
        recoverable: true
      }));
    });

    it('should not log to console when disabled', () => {
      service.configure({ logToConsole: false });
      const error = ProcessorError.deviceNotFound('device001');

      service.handle(error, context);

      expect(console.warn).not.toHaveBeenCalled();
      expect(console.error).not.toHaveBeenCalled();
    });
  });

  describe('handleUnknownError', () => {
    it('should wrap Error objects', () => {
      const originalError = new Error('Something went wrong');

      expect(() => service.handleUnknownError(originalError, context)).toThrow();

      expect(context.errors.length).toBe(1);
      expect(context.errors[0]).toContain('[UNKNOWN_ERROR]');
      expect(context.errors[0]).toContain('Something went wrong');
    });

    it('should wrap string errors', () => {
      expect(() => service.handleUnknownError('String error', context)).toThrow();

      expect(context.errors.length).toBe(1);
      expect(context.errors[0]).toContain('[UNKNOWN_ERROR]');
      expect(context.errors[0]).toContain('String error');
    });

    it('should include additional context', () => {
      service.configure({ throwOnNonRecoverable: false });
      const error = new Error('Test error');

      service.handleUnknownError(error, context, { customField: 'customValue' });

      expect(context.logs![0]).toEqual(jasmine.objectContaining({
        context: jasmine.objectContaining({
          customField: 'customValue'
        })
      }));
    });

    it('should pass through ProcessorError unchanged', () => {
      const processorError = ProcessorError.deviceNotFound('device001');

      service.handleUnknownError(processorError, context);

      expect(context.errors[0]).toContain('[DEVICE_NOT_FOUND]');
    });
  });

  describe('isRecoverable', () => {
    it('should return true for recoverable error codes', () => {
      expect(service.isRecoverable(ProcessorErrorCode.DEVICE_NOT_FOUND)).toBe(true);
      expect(service.isRecoverable(ProcessorErrorCode.EXTERNAL_ID_RESOLUTION_FAILED)).toBe(true);
    });

    it('should return false for non-recoverable error codes', () => {
      expect(service.isRecoverable(ProcessorErrorCode.DEVICE_CREATION_FAILED)).toBe(false);
      expect(service.isRecoverable(ProcessorErrorCode.CODE_EXECUTION_FAILED)).toBe(false);
      expect(service.isRecoverable(ProcessorErrorCode.PAYLOAD_PARSING_FAILED)).toBe(false);
    });
  });

  describe('getErrorSummary', () => {
    it('should count total errors', () => {
      service.configure({ throwOnNonRecoverable: false });

      service.handle(ProcessorError.deviceNotFound('device001'), context);
      service.handle(ProcessorError.deviceNotFound('device002'), context);

      const summary = service.getErrorSummary(context);

      expect(summary.total).toBe(2);
    });

    it('should categorize recoverable vs non-recoverable', () => {
      service.configure({ throwOnNonRecoverable: false });

      service.handle(ProcessorError.deviceNotFound('device001'), context);
      service.handle(ProcessorError.payloadParsingFailed('Invalid'), context);

      const summary = service.getErrorSummary(context);

      expect(summary.recoverable).toBe(1);
      expect(summary.nonRecoverable).toBe(1);
    });

    it('should count errors by code', () => {
      service.configure({ throwOnNonRecoverable: false });

      service.handle(ProcessorError.deviceNotFound('device001'), context);
      service.handle(ProcessorError.deviceNotFound('device002'), context);
      service.handle(ProcessorError.payloadParsingFailed('Invalid'), context);

      const summary = service.getErrorSummary(context);

      expect(summary.byCodes['DEVICE_NOT_FOUND']).toBe(2);
      expect(summary.byCodes['PAYLOAD_PARSING_FAILED']).toBe(1);
    });
  });

  describe('clearErrors', () => {
    it('should remove all errors from context', () => {
      service.handle(ProcessorError.deviceNotFound('device001'), context);

      expect(context.errors.length).toBe(1);

      service.clearErrors(context);

      expect(context.errors.length).toBe(0);
    });
  });

  describe('hasErrors', () => {
    it('should return false when no errors', () => {
      expect(service.hasErrors(context)).toBe(false);
    });

    it('should return true when errors exist', () => {
      service.handle(ProcessorError.deviceNotFound('device001'), context);

      expect(service.hasErrors(context)).toBe(true);
    });
  });

  describe('hasNonRecoverableErrors', () => {
    it('should return false when only recoverable errors', () => {
      service.handle(ProcessorError.deviceNotFound('device001'), context);

      expect(service.hasNonRecoverableErrors(context)).toBe(false);
    });

    it('should return true when non-recoverable errors exist', () => {
      service.configure({ throwOnNonRecoverable: false });
      service.handle(ProcessorError.payloadParsingFailed('Invalid'), context);

      expect(service.hasNonRecoverableErrors(context)).toBe(true);
    });

    it('should return true when mixed errors exist', () => {
      service.configure({ throwOnNonRecoverable: false });
      service.handle(ProcessorError.deviceNotFound('device001'), context);
      service.handle(ProcessorError.payloadParsingFailed('Invalid'), context);

      expect(service.hasNonRecoverableErrors(context)).toBe(true);
    });
  });
});
