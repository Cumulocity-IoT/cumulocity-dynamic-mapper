/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { CodeEvaluatorService, ExecutionContext } from './code-evaluator.service';

describe('CodeEvaluatorService', () => {
  let service: CodeEvaluatorService;

  beforeEach(() => {
    service = new CodeEvaluatorService();
  });

  describe('evaluate', () => {
    it('should execute simple JavaScript code', async () => {
      const code = 'return 42;';
      const ctx: ExecutionContext = {
        identifier: 'test',
        payload: {},
        topic: 'test/topic'
      };

      const result = await service.evaluate(code, ctx);

      expect(result.success).toBe(true);
      expect(result.result).toBe(42);
    });

    it('should access execution context', async () => {
      const code = 'return arg0.getPayload().temperature;';
      const ctx: ExecutionContext = {
        identifier: 'device001',
        payload: { temperature: 25.5 },
        topic: 'device/001/telemetry'
      };

      const result = await service.evaluate(code, ctx);

      expect(result.success).toBe(true);
      expect(result.result).toBe(25.5);
    });

    it('should capture console.log output', async () => {
      const code = 'console.log("Test message"); return "done";';
      const ctx: ExecutionContext = {
        identifier: 'test',
        payload: {},
        topic: 'test/topic'
      };

      const result = await service.evaluate(code, ctx);

      expect(result.success).toBe(true);
      expect(result.logs).toContain('Test message');
    });

    it('should capture multiple console calls', async () => {
      const code = `
        console.log("Log 1");
        console.info("Info 1");
        console.warn("Warning 1");
        return true;
      `;
      const ctx: ExecutionContext = {
        identifier: 'test',
        payload: {},
        topic: 'test/topic'
      };

      const result = await service.evaluate(code, ctx);

      expect(result.success).toBe(true);
      expect(result.logs.length).toBeGreaterThanOrEqual(3);
      expect(result.logs).toContain('Log 1');
      expect(result.logs).toContain('Info 1');
      expect(result.logs).toContain('Warning 1');
    });

    it('should handle syntax errors', async () => {
      const code = 'this is { invalid syntax';
      const ctx: ExecutionContext = {
        identifier: 'test',
        payload: {},
        topic: 'test/topic'
      };

      const result = await service.evaluate(code, ctx);

      expect(result.success).toBe(false);
      expect(result.error).toBeDefined();
      expect(result.error!.message).toContain('Unexpected');
    });

    it('should handle runtime errors', async () => {
      const code = 'throw new Error("Test error");';
      const ctx: ExecutionContext = {
        identifier: 'test',
        payload: {},
        topic: 'test/topic'
      };

      const result = await service.evaluate(code, ctx);

      expect(result.success).toBe(false);
      expect(result.error).toBeDefined();
      expect(result.error!.message).toContain('Test error');
    });

    it('should timeout after specified duration', async () => {
      const code = 'while(true) {}'; // Infinite loop
      const ctx: ExecutionContext = {
        identifier: 'test',
        payload: {},
        topic: 'test/topic'
      };

      const result = await service.evaluate(code, ctx, { timeoutMs: 100 });

      expect(result.success).toBe(false);
      expect(result.error).toBeDefined();
      expect(result.error!.message).toContain('timed out');
    }, 10000);

    it('should use default timeout', async () => {
      const code = 'while(true) {}';
      const ctx: ExecutionContext = {
        identifier: 'test',
        payload: {},
        topic: 'test/topic'
      };

      const startTime = Date.now();
      const result = await service.evaluate(code, ctx);
      const duration = Date.now() - startTime;

      expect(result.success).toBe(false);
      expect(result.error!.message).toContain('timed out');
      expect(duration).toBeLessThan(500); // Should timeout around 250ms
    }, 10000);

    it('should handle custom timeout configuration', async () => {
      const code = 'return "success";';
      const ctx: ExecutionContext = {
        identifier: 'test',
        payload: {},
        topic: 'test/topic'
      };

      const result = await service.evaluate(code, ctx, { timeoutMs: 1000 });

      expect(result.success).toBe(true);
      expect(result.result).toBe('success');
    });

    it('should work with Java-like ArrayList', async () => {
      const code = `
        const ArrayList = Java.type('java.util.ArrayList');
        const list = new ArrayList();
        list.add('item1');
        list.add('item2');
        return list.size();
      `;
      const ctx: ExecutionContext = {
        identifier: 'test',
        payload: {},
        topic: 'test/topic'
      };

      const result = await service.evaluate(code, ctx);

      expect(result.success).toBe(true);
      expect(result.result).toBe(2);
    });

    it('should work with SubstitutionResult', async () => {
      const code = `
        const SubstitutionResult = Java.type('dynamic.mapper.processor.model.SubstitutionResult');
        const result = new SubstitutionResult();
        result.addSubstitute('$.path', 'value');
        return result.getSubstitutions().get('$.path');
      `;
      const ctx: ExecutionContext = {
        identifier: 'test',
        payload: {},
        topic: 'test/topic'
      };

      const result = await service.evaluate(code, ctx);

      expect(result.success).toBe(true);
      expect(result.result).toBe('value');
    });

    it('should access topic from context', async () => {
      const code = 'return arg0.getTopic();';
      const ctx: ExecutionContext = {
        identifier: 'device001',
        payload: {},
        topic: 'org/site/device001/telemetry'
      };

      const result = await service.evaluate(code, ctx);

      expect(result.success).toBe(true);
      expect(result.result).toBe('org/site/device001/telemetry');
    });

    it('should access identifier from context', async () => {
      const code = 'return arg0.getGenericDeviceIdentifier();';
      const ctx: ExecutionContext = {
        identifier: { type: 'c8y_Serial', externalId: 'device001' },
        payload: {},
        topic: 'test/topic'
      };

      const result = await service.evaluate(code, ctx);

      expect(result.success).toBe(true);
      expect(result.result).toEqual({ type: 'c8y_Serial', externalId: 'device001' });
    });

    it('should handle complex payload structures', async () => {
      const code = 'return arg0.getPayload().sensor.readings.temperature;';
      const ctx: ExecutionContext = {
        identifier: 'test',
        payload: {
          sensor: {
            id: 'sensor001',
            readings: {
              temperature: 25.5,
              humidity: 60
            }
          }
        },
        topic: 'test/topic'
      };

      const result = await service.evaluate(code, ctx);

      expect(result.success).toBe(true);
      expect(result.result).toBe(25.5);
    });

    it('should handle errors with line numbers', async () => {
      const code = `
        const x = 1;
        const y = 2;
        throw new Error("Error on line 3");
      `;
      const ctx: ExecutionContext = {
        identifier: 'test',
        payload: {},
        topic: 'test/topic'
      };

      const result = await service.evaluate(code, ctx);

      expect(result.success).toBe(false);
      expect(result.error).toBeDefined();
      expect(result.error!.message).toContain('Error on line 3');
      expect(result.error!.location).toBeDefined();
    });

    it('should handle streamLogs configuration', async () => {
      const code = 'console.log("Test"); return 42;';
      const ctx: ExecutionContext = {
        identifier: 'test',
        payload: {},
        topic: 'test/topic'
      };

      const result = await service.evaluate(code, ctx, { streamLogs: false });

      expect(result.success).toBe(true);
      expect(result.logs).toContain('Test');
    });

    it('should clamp timeout to maximum', async () => {
      const code = 'return "done";';
      const ctx: ExecutionContext = {
        identifier: 'test',
        payload: {},
        topic: 'test/topic'
      };

      const result = await service.evaluate(code, ctx, { timeoutMs: 10000 });

      expect(result.success).toBe(true);
      // Should still execute successfully despite clamped timeout
    });

    it('should use default for negative timeout', async () => {
      const code = 'return "done";';
      const ctx: ExecutionContext = {
        identifier: 'test',
        payload: {},
        topic: 'test/topic'
      };

      const result = await service.evaluate(code, ctx, { timeoutMs: -100 });

      expect(result.success).toBe(true);
    });
  });
});
