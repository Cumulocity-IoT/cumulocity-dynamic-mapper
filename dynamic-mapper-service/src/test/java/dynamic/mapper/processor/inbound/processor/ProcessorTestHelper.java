/*
 * Copyright (c) 2025 Cumulocity GmbH.
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
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.processor.inbound.processor;

import java.lang.reflect.Field;

import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for common processor test operations.
 */
@Slf4j
public class ProcessorTestHelper {
    
    /**
     * Injects a field value into an object using reflection.
     * Searches through the class hierarchy to find the field.
     * 
     * @param target the object to inject the field into
     * @param fieldName the name of the field to inject
     * @param value the value to inject
     * @throws Exception if field cannot be found or injected
     */
    public static void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        
        if (field == null) {
            throw new NoSuchFieldException(
                    String.format("Field '%s' not found in class hierarchy of %s", 
                            fieldName, target.getClass().getSimpleName()));
        }
        
        field.setAccessible(true);
        field.set(target, value);
        log.debug("Injected field '{}' with value of type {}", fieldName, 
                value != null ? value.getClass().getSimpleName() : "null");
    }
    
    /**
     * Finds a field in the class hierarchy.
     * 
     * @param clazz the class to search
     * @param fieldName the name of the field
     * @return the Field object or null if not found
     */
    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> currentClass = clazz;
        
        while (currentClass != null) {
            try {
                return currentClass.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                currentClass = currentClass.getSuperclass();
            }
        }
        
        return null;
    }
    
    /**
     * Gets a field value from an object using reflection.
     * 
     * @param target the object to get the field from
     * @param fieldName the name of the field
     * @return the field value
     * @throws Exception if field cannot be found or accessed
     */
    public static Object getFieldValue(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        
        if (field == null) {
            throw new NoSuchFieldException(
                    String.format("Field '%s' not found in class hierarchy of %s", 
                            fieldName, target.getClass().getSimpleName()));
        }
        
        field.setAccessible(true);
        return field.get(target);
    }
    
    /**
     * Sets a field value in an object using reflection.
     * Convenience method that wraps exceptions in RuntimeException.
     * 
     * @param target the object to set the field in
     * @param fieldName the name of the field
     * @param value the value to set
     */
    public static void setFieldValue(Object target, String fieldName, Object value) {
        try {
            injectField(target, fieldName, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }
}