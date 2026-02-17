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

// Export example Smart Functions
export { onMessage as inboundBasic } from './examples/inbound-basic';
export { onMessage as inboundEnrichment } from './examples/inbound-enrichment';
export { onMessage as inboundWithState } from './examples/inbound-with-state';
export { onMessage as outboundBasic } from './examples/outbound-basic';
export { onMessage as outboundWithTransformation } from './examples/outbound-with-transformation';

// Default exports for convenience
import inboundBasicDefault from './examples/inbound-basic';
import inboundEnrichmentDefault from './examples/inbound-enrichment';
import inboundWithStateDefault from './examples/inbound-with-state';
import outboundBasicDefault from './examples/outbound-basic';
import outboundWithTransformationDefault from './examples/outbound-with-transformation';

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
};
