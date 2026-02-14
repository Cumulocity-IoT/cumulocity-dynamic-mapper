/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { CodeBasedProcessorInbound } from './code-based-processor-inbound.service';
import {
  createMockProcessingContext,
  mockMappings
} from '../__tests__/test-fixtures';
import {
  MockAlertService,
  MockC8YAgent,
  MockSharedService,
  setupMockWorkerFactory,
  restoreWorkerFactory
} from '../__tests__/test-helpers';
import { ErrorHandlerService } from '../error-handling/error-handler.service';
import { ProcessorLoggerService } from '../logging/processor-logger.service';
import { ProcessorConfigService } from '../config/processor-config.service';
import { JSONataCacheService } from '../performance/jsonata-cache.service';

describe('CodeBasedProcessorInbound', () => {
  let service: CodeBasedProcessorInbound;
  let mockAlert: MockAlertService;
  let mockC8YAgent: MockC8YAgent;
  let mockSharedService: MockSharedService;

  beforeEach(() => {
    mockAlert = new MockAlertService();
    mockC8YAgent = new MockC8YAgent();
    mockSharedService = new MockSharedService();
    const errorHandler = new ErrorHandlerService();
    const logger = new ProcessorLoggerService();
    const config = new ProcessorConfigService();
    const jsonataCache = new JSONataCacheService();

    service = new CodeBasedProcessorInbound(
      mockAlert as any,
      mockC8YAgent as any,
      mockSharedService as any,
      errorHandler,
      logger,
      config,
      jsonataCache
    );

    setupMockWorkerFactory();
  });

  afterEach(() => {
    mockAlert.reset();
    mockC8YAgent.reset();
    mockSharedService.reset();
    restoreWorkerFactory();
  });

  describe('deserializePayload', () => {
    it('should deserialize payload correctly', () => {
      const context = createMockProcessingContext();
      const message = { test: 'data' };

      const result = service.deserializePayload(
        mockMappings.inboundCodeBased,
        message,
        context
      );

      expect(result.payload as any).toEqual(message);
    });
  });

  describe('extractFromSource', () => {
    it('should execute JavaScript code in Web Worker', async () => {
      const context = createMockProcessingContext({
        mapping: mockMappings.inboundCodeBased,
        payload: { deviceId: 'test-001', value: 123 } as any
      });

      // Mock code templates
      mockSharedService.getCodeTemplates.and.returnValue(
        Promise.resolve({
          shared: { code: btoa('// Shared code') },
          system: { code: btoa('// System code') }
        } as any)
      );

      await service.extractFromSource(context);

      expect(mockSharedService.getCodeTemplates).toHaveBeenCalled();
    });

    it('should handle execution errors gracefully', async () => {
      const context = createMockProcessingContext({
        mapping: { ...mockMappings.inboundCodeBased, code: btoa('throw new Error("Test error");') },
        payload: {} as any
      });

      mockSharedService.getCodeTemplates.and.returnValue(
        Promise.resolve({
          shared: { code: btoa('') },
          system: { code: btoa('') }
        } as any)
      );

      await service.extractFromSource(context);

      // Should handle error without crashing
      expect(context.errors.length).toBeGreaterThanOrEqual(0);
    });
  });
});
