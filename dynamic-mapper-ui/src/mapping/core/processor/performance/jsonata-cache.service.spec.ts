/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { TestBed } from '@angular/core/testing';
import { JSONataCacheService } from './jsonata-cache.service';

describe('JSONataCacheService', () => {
  let service: JSONataCacheService;
  let compilerSpy: jasmine.Spy;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(JSONataCacheService);
    compilerSpy = jasmine.createSpy('compiler').and.callFake((expr: string) => ({
      expr,
      compiled: true
    }));
  });

  afterEach(() => {
    service.clear();
  });

  describe('getOrCompile', () => {
    it('should compile expression on first access', () => {
      const result = service.getOrCompile('$.value', compilerSpy);

      expect(compilerSpy).toHaveBeenCalledWith('$.value');
      expect(result).toEqual({ expr: '$.value', compiled: true });
    });

    it('should return cached expression on second access', () => {
      service.getOrCompile('$.value', compilerSpy);
      compilerSpy.calls.reset();

      const result = service.getOrCompile('$.value', compilerSpy);

      expect(compilerSpy).not.toHaveBeenCalled();
      expect(result).toEqual({ expr: '$.value', compiled: true });
    });

    it('should track cache hits', () => {
      service.getOrCompile('$.value', compilerSpy);
      service.getOrCompile('$.value', compilerSpy);

      const stats = service.getStats();
      expect(stats.hits).toBe(1);
      expect(stats.misses).toBe(1);
    });

    it('should track cache misses', () => {
      service.getOrCompile('$.value1', compilerSpy);
      service.getOrCompile('$.value2', compilerSpy);
      service.getOrCompile('$.value3', compilerSpy);

      const stats = service.getStats();
      expect(stats.misses).toBe(3);
      expect(stats.hits).toBe(0);
    });

    it('should handle multiple different expressions', () => {
      const result1 = service.getOrCompile('$.value1', compilerSpy);
      const result2 = service.getOrCompile('$.value2', compilerSpy);
      const result3 = service.getOrCompile('$.value1', compilerSpy);

      expect(compilerSpy).toHaveBeenCalledTimes(2);
      expect(result1).toEqual(result3);
      expect(result1).not.toEqual(result2);
    });
  });

  describe('LRU eviction', () => {
    beforeEach(() => {
      service.setMaxSize(3);
    });

    it('should evict oldest entry when cache is full', () => {
      service.getOrCompile('expr1', compilerSpy);
      service.getOrCompile('expr2', compilerSpy);
      service.getOrCompile('expr3', compilerSpy);
      service.getOrCompile('expr4', compilerSpy); // Should evict expr1

      expect(service.has('expr1')).toBe(false);
      expect(service.has('expr2')).toBe(true);
      expect(service.has('expr3')).toBe(true);
      expect(service.has('expr4')).toBe(true);
    });

    it('should update LRU order on cache hit', () => {
      service.getOrCompile('expr1', compilerSpy);
      service.getOrCompile('expr2', compilerSpy);
      service.getOrCompile('expr3', compilerSpy);
      service.getOrCompile('expr1', compilerSpy); // Move expr1 to end
      service.getOrCompile('expr4', compilerSpy); // Should evict expr2

      expect(service.has('expr1')).toBe(true);
      expect(service.has('expr2')).toBe(false);
      expect(service.has('expr3')).toBe(true);
      expect(service.has('expr4')).toBe(true);
    });
  });

  describe('setMaxSize', () => {
    it('should update max size', () => {
      service.setMaxSize(50);

      const stats = service.getStats();
      expect(stats.maxSize).toBe(50);
    });

    it('should evict entries when reducing max size', () => {
      service.getOrCompile('expr1', compilerSpy);
      service.getOrCompile('expr2', compilerSpy);
      service.getOrCompile('expr3', compilerSpy);

      service.setMaxSize(2);

      expect(service.getSize()).toBe(2);
      expect(service.has('expr1')).toBe(false);
      expect(service.has('expr2')).toBe(true);
      expect(service.has('expr3')).toBe(true);
    });

    it('should throw error for invalid max size', () => {
      expect(() => service.setMaxSize(0)).toThrowError(/at least 1/);
      expect(() => service.setMaxSize(-5)).toThrowError(/at least 1/);
    });
  });

  describe('clear', () => {
    it('should remove all cached expressions', () => {
      service.getOrCompile('expr1', compilerSpy);
      service.getOrCompile('expr2', compilerSpy);

      service.clear();

      expect(service.getSize()).toBe(0);
      expect(service.has('expr1')).toBe(false);
      expect(service.has('expr2')).toBe(false);
    });

    it('should reset statistics', () => {
      service.getOrCompile('expr1', compilerSpy);
      service.getOrCompile('expr1', compilerSpy);

      service.clear();

      const stats = service.getStats();
      expect(stats.hits).toBe(0);
      expect(stats.misses).toBe(0);
    });
  });

  describe('invalidate', () => {
    it('should remove specific expression', () => {
      service.getOrCompile('expr1', compilerSpy);
      service.getOrCompile('expr2', compilerSpy);

      const removed = service.invalidate('expr1');

      expect(removed).toBe(true);
      expect(service.has('expr1')).toBe(false);
      expect(service.has('expr2')).toBe(true);
    });

    it('should return false for non-existent expression', () => {
      const removed = service.invalidate('nonexistent');

      expect(removed).toBe(false);
    });
  });

  describe('getStats', () => {
    it('should return accurate statistics', () => {
      service.getOrCompile('expr1', compilerSpy);
      service.getOrCompile('expr2', compilerSpy);
      service.getOrCompile('expr1', compilerSpy);
      service.getOrCompile('expr3', compilerSpy);

      const stats = service.getStats();

      expect(stats.hits).toBe(1);
      expect(stats.misses).toBe(3);
      expect(stats.size).toBe(3);
      expect(stats.hitRate).toBe(25); // 1 hit out of 4 total
    });

    it('should calculate hit rate correctly', () => {
      service.getOrCompile('expr1', compilerSpy);
      service.getOrCompile('expr1', compilerSpy);
      service.getOrCompile('expr1', compilerSpy);
      service.getOrCompile('expr1', compilerSpy);

      const stats = service.getStats();

      expect(stats.hitRate).toBe(75); // 3 hits out of 4 total
    });

    it('should return 0 hit rate when no operations', () => {
      const stats = service.getStats();

      expect(stats.hitRate).toBe(0);
    });
  });

  describe('resetStats', () => {
    it('should reset counters without clearing cache', () => {
      service.getOrCompile('expr1', compilerSpy);
      service.getOrCompile('expr1', compilerSpy);

      service.resetStats();

      const stats = service.getStats();
      expect(stats.hits).toBe(0);
      expect(stats.misses).toBe(0);
      expect(stats.size).toBe(1); // Cache still has expr1
      expect(service.has('expr1')).toBe(true);
    });
  });

  describe('getCachedExpressions', () => {
    it('should return all cached expression strings', () => {
      service.getOrCompile('$.value1', compilerSpy);
      service.getOrCompile('$.value2', compilerSpy);
      service.getOrCompile('$.value3', compilerSpy);

      const cached = service.getCachedExpressions();

      expect(cached).toEqual(['$.value1', '$.value2', '$.value3']);
    });

    it('should return empty array when cache is empty', () => {
      const cached = service.getCachedExpressions();

      expect(cached).toEqual([]);
    });
  });

  describe('preload', () => {
    it('should preload expressions into cache', () => {
      const expressions = ['$.value1', '$.value2', '$.value3'];

      service.preload(expressions, compilerSpy);

      expect(compilerSpy).toHaveBeenCalledTimes(3);
      expect(service.getSize()).toBe(3);
      expressions.forEach(expr => {
        expect(service.has(expr)).toBe(true);
      });
    });

    it('should not recompile already cached expressions', () => {
      service.getOrCompile('$.value1', compilerSpy);
      compilerSpy.calls.reset();

      service.preload(['$.value1', '$.value2'], compilerSpy);

      expect(compilerSpy).toHaveBeenCalledTimes(1);
      expect(compilerSpy).toHaveBeenCalledWith('$.value2');
    });
  });

  describe('performance characteristics', () => {
    it('should handle large cache efficiently', () => {
      service.setMaxSize(1000);

      const start = performance.now();
      for (let i = 0; i < 1000; i++) {
        service.getOrCompile(`$.value${i}`, compilerSpy);
      }
      const duration = performance.now() - start;

      expect(duration).toBeLessThan(100); // Should complete in < 100ms
      expect(service.getSize()).toBe(1000);
    });

    it('should maintain LRU order efficiently', () => {
      service.setMaxSize(100);

      // Fill cache
      for (let i = 0; i < 100; i++) {
        service.getOrCompile(`$.expr${i}`, compilerSpy);
      }

      // Access some entries to update LRU
      for (let i = 0; i < 50; i++) {
        service.getOrCompile(`$.expr${i}`, compilerSpy);
      }

      // Add new entries (should evict expr50-expr99)
      for (let i = 100; i < 150; i++) {
        service.getOrCompile(`$.expr${i}`, compilerSpy);
      }

      // Oldest non-accessed entries should be gone
      expect(service.has('$.expr50')).toBe(false);
      expect(service.has('$.expr99')).toBe(false);

      // Recently accessed should remain
      expect(service.has('$.expr0')).toBe(true);
      expect(service.has('$.expr49')).toBe(true);
    });
  });
});
