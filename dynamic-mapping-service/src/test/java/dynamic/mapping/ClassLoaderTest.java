/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
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

 package dynamic.mapping;

 import java.io.BufferedReader;
 import java.io.File;
 import java.io.IOException;
 import java.io.InputStream;
 import java.io.InputStreamReader;
 import java.net.URI;
 import java.net.URL;
 import java.net.URLClassLoader;
 import java.util.Properties;
 
 public class ClassLoaderTest {
     
     public static void main(String[] args) throws IOException, ClassNotFoundException {
         // Create a File object and convert to URI, then to URL
         File jarFile = new File("/Users/ck/work/git/cumulocity-dynamic-mqtt-mapper/extension/target/mqtt-mapping-extension.jar");
         URL[] classLoaderUrls = new URL[]{jarFile.toURI().toURL()};
         
         // Alternative using URI directly (if you prefer)
         // URL[] classLoaderUrls = new URL[]{URI.create("file:///Users/ck/work/git/cumulocity-dynamic-mqtt-mapper/extension/target/mqtt-mapping-extension.jar").toURL()};
         
         URLClassLoader classLoader = URLClassLoader.newInstance(classLoaderUrls);
 
         InputStream resourceAsStream = classLoader.getResourceAsStream("extension.properties");
         if (resourceAsStream == null) {
             System.out.println("Resource 'extension.properties' not found");
             return;
         }
         
         BufferedReader buffered = new BufferedReader(new InputStreamReader(resourceAsStream));
         Properties props = new Properties();
         
         String line;
         while ((line = buffered.readLine()) != null) {
             System.out.println("---------------" + line);
         }
         
         // Reset the stream to read from the beginning for properties loading
         resourceAsStream = classLoader.getResourceAsStream("extension.properties");
         if (resourceAsStream != null) {
             props.load(resourceAsStream);
         }
         
         try {
             Class<?> clazz = classLoader.loadClass("dynamic.mapping.processor.protobuf.CustomEventOuter");
             System.out.println("Found:" + clazz.getName());
         } catch (ClassNotFoundException e) {
             System.out.println("Class not found: " + e.getMessage());
             throw e;
         } finally {
             classLoader.close(); // Close the class loader when done
         }
     }
 }