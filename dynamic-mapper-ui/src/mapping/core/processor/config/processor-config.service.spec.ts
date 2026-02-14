/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { TestBed } from '@angular/core/testing';
import { ProcessorConfigService, ProcessorConfig } from './processor-config.service';
import { ProcessingConfig } from '../processor.constants';

describe('ProcessorConfigService', () => {
  let service: ProcessorConfigService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(ProcessorConfigService);
  });

  afterEach(() => {
    service.reset();
  });

  describe('default configuration', () => {
    it('should have default timeout from constants', () => {
      expect(service.getCodeExecutionTimeout()).toBe(
        ProcessingConfig.DEFAULT_TIMEOUT_MS
      );
    });

    it('should have default max timeout from constants', () => {
      expect(service.getMaxTimeout()).toBe(ProcessingConfig.MAX_TIMEOUT_MS);
    });

    it('should have default min timeout', () => {
      expect(service.getMinTimeout()).toBe(50);
    });

    it('should stream console logs by default', () => {
      expect(service.shouldStreamConsoleLogs()).toBe(true);
    });

    it('should not use JSONata strict mode by default', () => {
      expect(service.isJsonataStrictMode()).toBe(false);
    });

    it('should have default max JSONPath depth', () => {
      expect(service.getMaxJsonPathDepth()).toBe(100);
    });

    it('should cache compiled expressions by default', () => {
      expect(service.shouldCacheCompiledExpressions()).toBe(true);
    });
  });

  describe('configure', () => {
    it('should update code execution timeout', () => {
      service.configure({ codeExecutionTimeoutMs: 500 });

      expect(service.getCodeExecutionTimeout()).toBe(500);
    });

    it('should update max timeout', () => {
      service.configure({ maxTimeoutMs: 10000 });

      expect(service.getMaxTimeout()).toBe(10000);
    });

    it('should update console log streaming', () => {
      service.configure({ streamConsoleLogs: false });

      expect(service.shouldStreamConsoleLogs()).toBe(false);
    });

    it('should update JSONata strict mode', () => {
      service.configure({ jsonataStrictMode: true });

      expect(service.isJsonataStrictMode()).toBe(true);
    });

    it('should update max JSONPath depth', () => {
      service.configure({ maxJsonPathDepth: 200 });

      expect(service.getMaxJsonPathDepth()).toBe(200);
    });

    it('should update expression caching', () => {
      service.configure({ cacheCompiledExpressions: false });

      expect(service.shouldCacheCompiledExpressions()).toBe(false);
    });

    it('should merge multiple configuration updates', () => {
      service.configure({ codeExecutionTimeoutMs: 500 });
      service.configure({ streamConsoleLogs: false });

      expect(service.getCodeExecutionTimeout()).toBe(500);
      expect(service.shouldStreamConsoleLogs()).toBe(false);
    });

    it('should throw error for invalid timeout', () => {
      expect(() => {
        service.configure({ codeExecutionTimeoutMs: 10 });
      }).toThrowError(/below minimum/);
    });

    it('should throw error for timeout exceeding maximum', () => {
      expect(() => {
        service.configure({ codeExecutionTimeoutMs: 10000 });
      }).toThrowError(/exceeds maximum/);
    });

    it('should throw error for invalid max timeout', () => {
      expect(() => {
        service.configure({ maxTimeoutMs: -100 });
      }).toThrowError(/must be greater than 0/);
    });

    it('should throw error for invalid min timeout', () => {
      expect(() => {
        service.configure({ minTimeoutMs: 0 });
      }).toThrowError(/must be greater than 0/);
    });

    it('should throw error for invalid max depth', () => {
      expect(() => {
        service.configure({ maxJsonPathDepth: -1 });
      }).toThrowError(/must be greater than 0/);
    });
  });

  describe('getConfig', () => {
    it('should return complete configuration', () => {
      const config = service.getConfig();

      expect(config.codeExecutionTimeoutMs).toBeDefined();
      expect(config.maxTimeoutMs).toBeDefined();
      expect(config.minTimeoutMs).toBeDefined();
      expect(config.streamConsoleLogs).toBeDefined();
      expect(config.jsonataStrictMode).toBeDefined();
      expect(config.maxJsonPathDepth).toBeDefined();
      expect(config.cacheCompiledExpressions).toBeDefined();
    });

    it('should return immutable copy', () => {
      const config1 = service.getConfig();
      const config2 = service.getConfig();

      expect(config1).not.toBe(config2);
      expect(config1).toEqual(config2);
    });

    it('should reflect configuration changes', () => {
      service.configure({ codeExecutionTimeoutMs: 1000 });

      const config = service.getConfig();

      expect(config.codeExecutionTimeoutMs).toBe(1000);
    });
  });

  describe('reset', () => {
    it('should reset to default values', () => {
      service.configure({
        codeExecutionTimeoutMs: 1000,
        streamConsoleLogs: false,
        jsonataStrictMode: true
      });

      service.reset();

      expect(service.getCodeExecutionTimeout()).toBe(
        ProcessingConfig.DEFAULT_TIMEOUT_MS
      );
      expect(service.shouldStreamConsoleLogs()).toBe(true);
      expect(service.isJsonataStrictMode()).toBe(false);
    });
  });

  describe('validateTimeout', () => {
    it('should accept valid timeout', () => {
      expect(() => {
        service.validateTimeout(250);
      }).not.toThrow();
    });

    it('should reject timeout below minimum', () => {
      expect(() => {
        service.validateTimeout(10);
      }).toThrowError(/below minimum/);
    });

    it('should reject timeout above maximum', () => {
      expect(() => {
        service.validateTimeout(10000);
      }).toThrowError(/exceeds maximum/);
    });

    it('should accept timeout at minimum boundary', () => {
      expect(() => {
        service.validateTimeout(50);
      }).not.toThrow();
    });

    it('should accept timeout at maximum boundary', () => {
      expect(() => {
        service.validateTimeout(5000);
      }).not.toThrow();
    });
  });

  describe('clampTimeout', () => {
    it('should return value within range unchanged', () => {
      expect(service.clampTimeout(250)).toBe(250);
    });

    it('should clamp value below minimum to minimum', () => {
      expect(service.clampTimeout(10)).toBe(50);
    });

    it('should clamp value above maximum to maximum', () => {
      expect(service.clampTimeout(10000)).toBe(5000);
    });

    it('should return minimum when exactly at minimum', () => {
      expect(service.clampTimeout(50)).toBe(50);
    });

    it('should return maximum when exactly at maximum', () => {
      expect(service.clampTimeout(5000)).toBe(5000);
    });
  });

  describe('configuration boundaries', () => {
    it('should allow updating min and max timeouts together', () => {
      service.configure({
        minTimeoutMs: 100,
        maxTimeoutMs: 10000
      });

      expect(service.getMinTimeout()).toBe(100);
      expect(service.getMaxTimeout()).toBe(10000);
    });

    it('should validate against new boundaries after update', () => {
      service.configure({
        minTimeoutMs: 100,
        maxTimeoutMs: 1000
      });

      expect(() => {
        service.validateTimeout(50);
      }).toThrowError(/below minimum/);

      expect(() => {
        service.validateTimeout(2000);
      }).toThrowError(/exceeds maximum/);
    });
  });
});
