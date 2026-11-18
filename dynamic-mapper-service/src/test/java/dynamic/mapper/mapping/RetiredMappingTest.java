/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dynamic.mapper.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;

import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingType;
import dynamic.mapper.model.TransformationType;
import dynamic.mapper.model.validation.ValidationError;

/**
 * Mapping types that are no longer supported: PROTOBUF_INTERNAL and SUBSTITUTION_AS_CODE. They are
 * not loaded (the service events name them), and cannot be created, updated or activated.
 */
@SuppressWarnings({ "deprecation", "removal" })
class RetiredMappingTest {

    private static Mapping mapping(MappingType type, TransformationType transformation) {
        return Mapping.builder().direction(Direction.INBOUND).mappingType(type).transformationType(transformation)
                .name("m").identifier("id1").mappingTopic("a/b").mappingTopicSample("a/b").build();
    }

    @Test
    void retiredTypesAreRecognised() {
        assertTrue(Mapping.isRetired(mapping(MappingType.PROTOBUF_INTERNAL, TransformationType.JSONATA)));
        assertTrue(Mapping.isRetired(mapping(MappingType.JSON, TransformationType.SUBSTITUTION_AS_CODE)));
        assertFalse(Mapping.isRetired(mapping(MappingType.JSON, TransformationType.JSONATA)));
        assertFalse(Mapping.isRetired(mapping(MappingType.ANY_PAYLOAD, TransformationType.SMART_FUNCTION)));
        assertFalse(Mapping.isRetired(null));
    }

    @Test
    void validatorRejectsRetiredTypes() {
        MappingValidator validator = new MappingValidator(null, null, null);
        assertEquals(List.of(ValidationError.Retired_Mapping_Type_Or_Transformation_Type),
                validator.validateNotRetired(mapping(MappingType.PROTOBUF_INTERNAL, TransformationType.JSONATA)));
        assertEquals(List.of(ValidationError.Retired_Mapping_Type_Or_Transformation_Type),
                validator.validateNotRetired(mapping(MappingType.JSON, TransformationType.SUBSTITUTION_AS_CODE)));
        assertTrue(validator.validateNotRetired(mapping(MappingType.JSON, TransformationType.JSONATA)).isEmpty());
    }

    @Test
    void repositoryRefusesToPrepareARetiredMapping() {
        MappingRepository repository = new MappingRepository(mock(ServiceRegistry.class));
        Mapping retired = mapping(MappingType.JSON, TransformationType.SUBSTITUTION_AS_CODE);
        assertThrows(IllegalArgumentException.class, () -> repository.prepareForCreate("t", retired));
        assertThrows(IllegalArgumentException.class, () -> repository.prepareForUpdate("t", retired, true, true));
    }

    @Test
    void retiredMappingsAreNotLoadedAndReportedOncePerManagedObject() {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .registerModule(new com.fasterxml.jackson.databind.module.SimpleModule()
                        .addSerializer(GId.class, new com.fasterxml.jackson.databind.ser.std.StdSerializer<GId>(GId.class) {
                            @Override
                            public void serialize(GId value, com.fasterxml.jackson.core.JsonGenerator gen,
                                    com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                                gen.writeString(value.getValue());
                            }
                        }))
                .addMixIn(ManagedObjectRepresentation.class, AttributesAtTopLevel.class);
        when(registry.getObjectMapper()).thenReturn(objectMapper);
        MappingRepository repository = new MappingRepository(registry);

        ManagedObjectRepresentation proto = managedObject("101", "PROTOBUF_INTERNAL", "JSONATA");
        ManagedObjectRepresentation sac = managedObject("102", "JSON", "SUBSTITUTION_AS_CODE");
        ManagedObjectRepresentation ok = managedObject("103", "JSON", "JSONATA");
        ManagedObjectCollection moc = collectionOf(proto, sac, ok);

        MappingRepository.LoadEffects effects = mock(MappingRepository.LoadEffects.class);
        List<Mapping> first = repository.findAll("t", Direction.UNSPECIFIED, moc, effects);

        assertEquals(List.of("103"), first.stream().map(Mapping::getId).toList());
        // Both retired mappings are named in the service events; nothing is migrated or persisted for them.
        verify(effects, times(2)).sendLoadingError(eq("t"), any(), any());
        verify(effects, never()).persistMigratedMapping(eq("t"), any());

        // A reload must not flood the service events again.
        repository.findAll("t", Direction.UNSPECIFIED, moc, effects);
        verify(effects, times(2)).sendLoadingError(eq("t"), any(), any());
    }

    /** The platform's mapper writes the fragments of a managed object at its top level; a plain one nests them under "attrs". */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties("attrs")
    abstract static class AttributesAtTopLevel {
        @com.fasterxml.jackson.annotation.JsonAnyGetter
        abstract Map<String, Object> getAttrs();
    }

    private static ManagedObjectRepresentation managedObject(String id, String mappingType, String transformationType) {
        ManagedObjectRepresentation mo = new ManagedObjectRepresentation();
        mo.setId(GId.asGId(id));
        mo.setProperty("type", "d11r_mapping");
        mo.setProperty("d11r_mapping", Map.of(
                "direction", "INBOUND", "name", "m" + id, "identifier", "i" + id,
                "mappingType", mappingType, "transformationType", transformationType,
                "mappingTopic", "a/b", "mappingTopicSample", "a/b",
                "sourceTemplate", "{}", "targetTemplate", "{}", "active", false));
        return mo;
    }

    private static ManagedObjectCollection collectionOf(ManagedObjectRepresentation... mos) {
        ManagedObjectCollection moc = mock(ManagedObjectCollection.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(moc.get().allPages()).thenReturn(List.of(mos));
        return moc;
    }
}
