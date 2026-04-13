/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Smart Function Examples - Main Entry Point
 *
 * This module exports all example Smart Functions and types.
 */

// Export all types
export * from './types';

// Export V1 example Smart Functions
export { onMessage as inboundBasic } from './examples/inbound-basic';
export { onMessage as inboundEnrichment } from './examples/inbound-enrichment';
export { onMessage as inboundWithState } from './examples/inbound-with-state';
export { onMessage as outboundBasic } from './examples/outbound-basic';
export { onMessage as outboundWithTransformation } from './examples/outbound-with-transformation';

// Export V2 example Smart Functions
export { onMessage as v2InboundTyped } from './examples/v2-inbound-typed';
export { onMessage as v2InboundEnrichment } from './examples/v2-inbound-enrichment';
export { onMessage as v2OutboundTyped } from './examples/v2-outbound-typed';

// Default exports for convenience
import inboundBasicDefault from './examples/inbound-basic';
import inboundEnrichmentDefault from './examples/inbound-enrichment';
import inboundWithStateDefault from './examples/inbound-with-state';
import outboundBasicDefault from './examples/outbound-basic';
import outboundWithTransformationDefault from './examples/outbound-with-transformation';
import v2InboundTypedDefault from './examples/v2-inbound-typed';
import v2InboundEnrichmentDefault from './examples/v2-inbound-enrichment';
import v2OutboundTypedDefault from './examples/v2-outbound-typed';

export const examples = {
  inbound: {
    basic: inboundBasicDefault,
    enrichment: inboundEnrichmentDefault,
    withState: inboundWithStateDefault,
  },
  outbound: {
    basic: outboundBasicDefault,
    withTransformation: outboundWithTransformationDefault,
  },
  v2: {
    inboundTyped: v2InboundTypedDefault,
    inboundEnrichment: v2InboundEnrichmentDefault,
    outboundTyped: v2OutboundTypedDefault,
  },
};
